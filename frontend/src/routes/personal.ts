import crypto from 'crypto';
import { Request, Response, NextFunction, Router } from 'express';
import { AppServices } from '../composition-root';
import { AppConfig } from '../config';
import { personalReportPeopleFor } from '../domain/personal-report-people';
import { Principal } from '../domain/model/principal';
import { captureCurrentTraceContext } from '../telemetry/trace-context';
import * as logger from '../telemetry/logger';

var express = require('express');

// Short, random, and easy to read out over the phone; printed in the document's footer so a
// given printout can be matched against the execution that produced it.
function newRequestId(): string {
  return crypto.randomBytes(6).toString('hex').toUpperCase();
}

// Reports name the person parameter differently (the DSGVO-Auskunft predates the Bescheinigungen);
// the person id is passed under whichever of these a report actually declares.
const PERSON_PARAMETER_NAMES = ['A_Mitgliedsnummer', 'mitgliedId'];
const REQUEST_ID_PARAMETER_NAME = 'X_RequestId';

/**
 * A deliberately minimal page: pick a person (only the user themself, plus people they manage)
 * and a document, get a PDF. Everything else a report needs - group, Mitgliedsnummer,
 * Anfrage-ID, output format - is set here on the server, never taken from the form.
 */
export function createPersonalRouter(services: AppServices, config: AppConfig): Router {
  const router = express.Router();
  const { reportExecutionService, metadataService } = services;

  router.get('/personal', async function (req: Request, res: Response, next: NextFunction) {
    try {
      const principal = req.principal as Principal;
      res.locals.principal = principal;
      res.locals.title = 'Bescheinigungen';

      const people = personalReportPeopleFor(principal);
      // A requested person the user may not act for is an error, not something to silently
      // replace by the user themself - they would otherwise download the wrong person's document.
      const requestedPersonId = typeof req.query.personId === 'string' ? req.query.personId : undefined;
      const forbiddenPersonId =
        requestedPersonId !== undefined && !people.some((person) => person.id === requestedPersonId)
          ? requestedPersonId
          : undefined;
      if (forbiddenPersonId !== undefined) {
        logger.warn('authz.denied', {
          'principal.id': principal.id,
          reason: 'person',
          'person.id': forbiddenPersonId,
        });
        res.status(403);
      }

      res.render('personal', {
        forbiddenPersonId,
        people,
        reports: await metadataService.findIndividualPersonReports(),
        selectedPersonId: req.query.personId,
        selectedReportId: req.query.reportId,
      });
    } catch (err) {
      next(err);
    }
  });

  router.post('/personal', async function (req: Request, res: Response, next: NextFunction) {
    try {
      const principal = req.principal as Principal;
      const groupId = config.personalReports.groupId;

      // Re-checked here rather than trusting the form: only the user themself and people they
      // manage, and only reports explicitly flagged as individualPersonReport.
      const personId = String(req.body.personId ?? '');
      const person = personalReportPeopleFor(principal).find((candidate) => candidate.id === personId);
      if (!person) {
        logger.warn('authz.denied', { 'principal.id': principal.id, reason: 'person', 'person.id': personId });
        res.status(403).send('Not authorized for this person.');
        return;
      }

      const reportId = String(req.body.reportId ?? '');
      const report = (await metadataService.findIndividualPersonReports()).find(
        (candidate) => candidate.id === reportId,
      );
      if (!report) {
        logger.warn('authz.denied', { 'principal.id': principal.id, reason: 'report', 'report.id': reportId });
        res.status(403).send('Not authorized for this report.');
        return;
      }

      // Only parameters the report declares are sent, so reports without a request id (or with
      // a differently named person parameter) work without special cases.
      const declared = new Set((await metadataService.getParameterFor(reportId)).map((parameter) => parameter.name));
      const personParameter = PERSON_PARAMETER_NAMES.find((name) => declared.has(name));
      if (!personParameter) {
        res.status(500).send('Der Bericht deklariert keinen Personen-Parameter.');
        return;
      }
      const requestId = newRequestId();
      const parameter: Record<string, unknown> = { p_gruppe_id: groupId, [personParameter]: person.id };
      if (declared.has(REQUEST_ID_PARAMETER_NAME)) {
        parameter[REQUEST_ID_PARAMETER_NAME] = requestId;
      }

      const executionId = crypto.randomUUID();
      await reportExecutionService.executeReport({
        executionId,
        reportId,
        parameter,
        outputFormat: 'pdf',
      });

      (req.session.ownedExecutionIds ??= []).push(executionId);
      (req.session.executionTraceContext ??= {})[executionId] = captureCurrentTraceContext();
      (req.session.executionSelections ??= {})[executionId] = {
        reportTitle: report.title,
        items: [
          { label: 'Person', value: person.name },
          { label: 'Dokument', value: report.title },
          ...(declared.has(REQUEST_ID_PARAMETER_NAME) ? [{ label: 'Anfrage-ID', value: requestId }] : []),
        ],
        newUrl: '/personal',
        newLabel: 'Neues Dokument',
        regenerateUrl: `/personal?${new URLSearchParams({ personId, reportId }).toString()}`,
      };

      logger.event('report.trigger', {
        'principal.id': principal.id,
        'report.id': reportId,
        'execution.id': executionId,
        'person.id': person.id,
        'request.id': requestId,
      });

      res.redirect(`/executions/${executionId}`);
    } catch (err) {
      next(err);
    }
  });

  return router;
}
