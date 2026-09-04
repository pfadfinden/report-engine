import crypto from 'crypto';
import { Request, Response, NextFunction, Router } from 'express';
import { AppServices } from '../composition-root';
import { AppConfig } from '../config';
import { Principal } from '../domain/model/principal';
import { ReportExecutionStatus } from '../domain/model/report-execution-task.model';

var express = require('express');
var createError = require('http-errors');

const PARAMETER_FIELD_PREFIX = 'p_';

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
      // the GET / route only filters what an honest browser can *submit*,
      // it doesn't stop a crafted POST from naming a group/report the
      // caller has no access to.
      const principal = req.principal as Principal;
      const availableGroups = await groupsService.findFor(principal);
      const selectedGroup = availableGroups.find((group) => group.id === groupId);
      if (!selectedGroup) {
        res.status(403).send('Not authorized for this group.');
        return;
      }

      const availableReports = await metadataService.findFor(selectedGroup.type);
      const selectedReport = availableReports.find((report) => report.id === reportId);
      if (!selectedReport) {
        res.status(403).send('Not authorized for this report.');
        return;
      }

      const parameter: Record<string, unknown> = { p_gruppe_id: groupId };
      for (const [key, value] of Object.entries(req.body)) {
        if (key.startsWith(PARAMETER_FIELD_PREFIX)) {
          parameter[key.substring(PARAMETER_FIELD_PREFIX.length)] = value;
        }
      }

      const executionId = crypto.randomUUID();
      await reportExecutionService.executeReport({
        executionId,
        reportId,
        parameter,
        outputFormat,
      });

      (req.session.ownedExecutionIds ??= []).push(executionId);

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

      const status = await reportExecutionService.status(executionId);
      if (status !== ReportExecutionStatus.DONE) {
        res.status(409).send('Report is not ready yet.');
        return;
      }

      const fileUrl = await reportExecutionService.downloadUrl(executionId);
      const fileRes = await fetch(fileUrl);
      if (!fileRes.ok) {
        throw new Error(`Failed to fetch report file: ${fileRes.status}`);
      }

      res.setHeader('Content-Type', fileRes.headers.get('content-type') ?? 'application/octet-stream');
      res.setHeader('Content-Disposition', fileRes.headers.get('content-disposition') ?? 'attachment');
      res.send(Buffer.from(await fileRes.arrayBuffer()));
    } catch (err) {
      next(err);
    }
  });

  return router;
}
