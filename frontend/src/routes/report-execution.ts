import crypto from 'crypto';
import { Request, Response, NextFunction, Router } from 'express';
import { AppServices } from '../composition-root';
import { AppConfig } from '../config';
import { ReportExecutionStatus } from '../domain/model/report-execution-task.model';

var express = require('express');

const PARAMETER_FIELD_PREFIX = 'p_';

export function createReportExecutionRouter(services: AppServices, config: AppConfig): Router {
  const router = express.Router();
  const { reportExecutionService } = services;

  // Triggers a report execution and redirects to a status page the user can
  // watch (auto-refreshing) until the download is ready. Reached from the
  // "Report generieren" form's formaction="./generate" in index.pug.
  router.post('/generate', async function (req: Request, res: Response, next: NextFunction) {
    try {
      const reportId = req.body.reportId as string;
      const groupId = req.body.groupId as string;
      const outputFormat = req.body.outputFormat as string;

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

      res.redirect(`/executions/${executionId}`);
    } catch (err) {
      next(err);
    }
  });

  router.get('/executions/:executionId', async function (req: Request, res: Response, next: NextFunction) {
    try {
      const executionId = req.params.executionId;
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
  // when REPORT_DOWNLOAD_MODE=proxy is actually in effect (see above) - the
  // frontend's own session (requireAuth, applied where this router is
  // mounted) is what gates "can this user have this file", exactly the check
  // a bare signed URL can't express (it only proves "a request to /download
  // for this executionId happened", not who that was for).
  router.get('/executions/:executionId/file', async function (req: Request, res: Response, next: NextFunction) {
    try {
      const executionId = req.params.executionId;
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
