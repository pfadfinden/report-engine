import crypto from 'crypto';
import { Readable } from 'node:stream';
import { Request, Response, NextFunction, Router } from 'express';
import { AppServices } from '../composition-root';
import { AppConfig } from '../config';
import { Principal } from '../domain/model/principal';
import { Parameter } from '../domain/model/report';
import { ReportExecutionStatus } from '../domain/model/report-execution-task.model';
import { captureCurrentTraceContext, withStoredTraceContext } from '../telemetry/trace-context';
import * as logger from '../telemetry/logger';

var express = require('express');
var createError = require('http-errors');

const PARAMETER_FIELD_PREFIX = 'p_';

// Report files are typically small, but a full report render/export can legitimately take a
// while on the executor side before the download endpoint even starts responding - generous
// enough to not fire on a slow-but-healthy report, short enough to not hang forever on a dead one.
const FILE_PROXY_TIMEOUT_MS = 120_000;

const PARAMETER_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

// Matches the declared parameter's HTML input type (see index.pug, which renders
// `input(type=parameter.type, ...)` directly from this same value) against the submitted value.
// This only rejects clearly malformed input (an object/array where a scalar is expected, or a
// value that isn't even shaped like the declared type) - report parameters otherwise still go
// through ParameterCoercion.coerce on the executor side against the report's own compiled
// parameter types, which this frontend has no visibility into.
function isValidParameterValue(type: string, value: unknown): boolean {
  if (value === null || typeof value === 'object') {
    return false;
  }
  const stringValue = String(value);
  switch (type) {
    case 'number':
      return stringValue === '' || Number.isFinite(Number(stringValue));
    case 'date':
      return stringValue === '' || PARAMETER_DATE_PATTERN.test(stringValue);
    default:
      return true;
  }
}

// An executionId is otherwise just an unguessable UUID with no owner tracked
// anywhere in the executor - this is what actually stops a different logged-in
// user's session from viewing or downloading someone else's report.
function requireOwnExecution(req: Request, next: NextFunction, executionId: string) {
  if (!req.session.ownedExecutionIds?.includes(executionId)) {
    next(createError(404, 'Nicht gefunden'));
    return false;
  }
  return true;
}

export function createReportExecutionRouter(services: AppServices, config: AppConfig): Router {
  const router = express.Router();
  const { reportExecutionService, groupsService, metadataService } = services;

  // Triggers a report execution and redirects to a status page the user can
  // watch (auto-refreshing) until the download is ready. Reached from the
  // "Report generieren" form's formaction="./generate" in index.pug.
  router.post('/generate', async function (req: Request, res: Response, next: NextFunction) {
    try {
      const reportId = req.body.reportId as string;
      const groupId = req.body.groupId as string;
      const outputFormat = req.body.outputFormat as string;

      // Re-check authorization here rather than trusting the form fields:
      const principal = req.principal as Principal;
      const availableGroups = await groupsService.findFor(principal);
      const selectedGroup = availableGroups.find((group) => group.id === groupId);
      if (!selectedGroup) {
        logger.warn('authz.denied', { 'principal.id': principal.id, reason: 'group', 'group.id': groupId });
        res.status(403).send('Not authorized for this group.');
        return;
      }

      const availableReports = await metadataService.findFor(selectedGroup.type);
      const selectedReport = availableReports.find((report) => report.id === reportId);
      if (!selectedReport) {
        logger.warn('authz.denied', {
          'principal.id': principal.id,
          reason: 'report',
          'group.id': groupId,
          'report.id': reportId,
        });
        res.status(403).send('Not authorized for this report.');
        return;
      }

      const declaredParameters = await metadataService.getParameterFor(reportId);
      const declaredParametersByName = new Map<string, Parameter>(
        declaredParameters.map((declared) => [declared.name, declared]),
      );

      const parameter: Record<string, unknown> = { p_gruppe_id: groupId };
      for (const [key, value] of Object.entries(req.body)) {
        if (!key.startsWith(PARAMETER_FIELD_PREFIX)) {
          continue;
        }
        const name = key.substring(PARAMETER_FIELD_PREFIX.length);
        const declared = declaredParametersByName.get(name);
        if (!declared) {
          res.status(400).send(`Unbekannter Parameter: ${name}`);
          return;
        }
        if (!isValidParameterValue(declared.type, value)) {
          res.status(400).send(`Ungültiger Wert für Parameter "${name}".`);
          return;
        }
        parameter[name] = value;
      }

      const executionId = crypto.randomUUID();
      await reportExecutionService.executeReport({
        executionId,
        reportId,
        parameter,
        outputFormat,
      });

      (req.session.ownedExecutionIds ??= []).push(executionId);
      (req.session.executionTraceContext ??= {})[executionId] = captureCurrentTraceContext();

      logger.event('report.trigger', {
        'principal.id': principal.id,
        'report.id': reportId,
        'execution.id': executionId,
        'group.id': groupId,
      });

      res.redirect(`/executions/${executionId}`);
    } catch (err) {
      next(err);
    }
  });

  router.get('/executions/:executionId', async function (req: Request, res: Response, next: NextFunction) {
    try {
      const executionId = String(req.params.executionId);
      if (!requireOwnExecution(req, next, executionId)) {
        return;
      }

      const traceContext = req.session.executionTraceContext?.[executionId];
      const { status, downloadUrl } = await withStoredTraceContext(traceContext, async () => {
        const status = await reportExecutionService.status(executionId);

        // "direct" hands the executor's own signed URL straight to the
        // browser (requires the executor to be browser-reachable); "proxy"
        // (default, recommended) links to this frontend's own /file route
        // instead, so the executor never needs to be. See config.ts for the
        // full tradeoff.
        const downloadUrl =
          status === ReportExecutionStatus.DONE
            ? config.execution.downloadMode === 'direct'
              ? await reportExecutionService.downloadUrl(executionId)
              : `/executions/${executionId}/file`
            : undefined;

        return { status, downloadUrl };
      });

      res.render('execution-status', {
        title: 'Bericht wird erstellt',
        executionId,
        status,
        statusLabel: ReportExecutionStatus[status],
        downloadUrl,
      });
    } catch (err) {
      next(err);
    }
  });

  // Proxies the finished report through the frontend rather than handing the
  // executor's (signed) download URL straight to the browser. Only reachable
  // when REPORT_DOWNLOAD_MODE=proxy is actually in effect (see above) -
  // requireOwnExecution above is what gates "can this session have this
  // file", exactly the check a bare signed URL can't express (it only proves
  // "a request to /download for this executionId happened", not who that was
  // for).
  router.get('/executions/:executionId/file', async function (req: Request, res: Response, next: NextFunction) {
    try {
      const executionId = String(req.params.executionId);
      if (!requireOwnExecution(req, next, executionId)) {
        return;
      }

      const traceContext = req.session.executionTraceContext?.[executionId];
      await withStoredTraceContext(traceContext, async () => {
        const status = await reportExecutionService.status(executionId);
        if (status !== ReportExecutionStatus.DONE) {
          res.status(409).send('Report is not ready yet.');
          return;
        }

        const fileUrl = await reportExecutionService.downloadUrl(executionId);
        const fileRes = await fetch(fileUrl, { signal: AbortSignal.timeout(FILE_PROXY_TIMEOUT_MS) });
        if (!fileRes.ok) {
          throw new Error(`Failed to fetch report file: ${fileRes.status}`);
        }

        res.setHeader('Content-Type', fileRes.headers.get('content-type') ?? 'application/octet-stream');
        res.setHeader('Content-Disposition', fileRes.headers.get('content-disposition') ?? 'attachment');
        // Streamed straight through rather than buffered via arrayBuffer(): a full report can be
        // large enough that holding the whole thing in memory per concurrent download risks OOM.
        if (fileRes.body) {
          await new Promise<void>((resolve, reject) => {
            const stream = Readable.fromWeb(fileRes.body as import('stream/web').ReadableStream);
            stream.on('error', reject);
            res.on('error', reject);
            res.on('finish', resolve);
            stream.pipe(res);
          });
        } else {
          res.end();
        }
      });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
