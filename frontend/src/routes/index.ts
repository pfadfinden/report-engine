import { Request, Response, NextFunction, Router } from 'express';
import { Report } from '../domain/model/report';
import { Principal } from '../domain/model/principal';
import { Group } from '../domain/model/group';
import { AppServices } from '../composition-root';
import { sortGroupsHierarchically } from './group-hierarchy';

var express = require('express');

export function createIndexRouter(services: AppServices): Router {
  const router = express.Router();
  const { groupsService, metadataService } = services;

  /* GET home page. */
  router.get('/', async function (req: Request, res: Response, next: NextFunction) {
    try {
      const requestParams = req.query;
      const request = {
        timestamp: new Date().toISOString(),
        url: req.originalUrl,
        method: req.method,
      };

      const principal = req.principal as Principal;
      res.locals.principal = principal;
      res.locals.title = 'Bericht erstellen';

      const availableGroups = await groupsService.findFor(principal);

      let selectedGroup: Group | undefined = undefined;
      let availableReports: readonly Report[] | undefined = undefined;
      let selectedReport: Report | undefined = undefined;

      // Re-evaluated at each render call below since selectedGroup/selectedReport are only
      // filled in progressively as the branches below narrow down the request.
      const buildDebugLines = () => [
        `# Angefragt: ${request.method} ${request.url}`,
        `# Anfragedatum: ${request.timestamp}`,
        `# Ausgewählte Gruppe: ${selectedGroup ? selectedGroup.id : '~Keine~'}`,
        `# Ausgewählter Bericht: ${selectedReport ? selectedReport.id : '~Keiner~'}`,
        `# Ausgewählte Berichtsversion: ${selectedReport ? selectedReport.version : '~Keine~'}`,
      ];

      if (requestParams.groupId) {
        selectedGroup = availableGroups.find((group) => group.id === requestParams.groupId);
      }

      // Reports are looked up per group type anyway, so reuse those lookups
      // to drop group types that have no report at all from the picker.
      // A deep-linked group is kept regardless, so it still shows up
      // selected and step 2 can explain that no report exists for it.
      const reportsByType = new Map<string, readonly Report[]>(
        await Promise.all(
          [...new Set(availableGroups.map((group) => group.type))].map(
            async (type) => [type, await metadataService.findFor(type)] as const,
          ),
        ),
      );
      const selectableGroups = availableGroups.filter(
        (group) => group.id === selectedGroup?.id || (reportsByType.get(group.type)?.length ?? 0) > 0,
      );
      const groupOptions = sortGroupsHierarchically(selectableGroups, availableGroups);

      if (requestParams.groupId) {
        if (!selectedGroup) {
          // -- Error: a non-existing group or one with insufficent access-rights was selected

          res.render('index', {
            availableGroups,
            groupOptions,
            availableReports,
            selectedGroup,
            selectedReport,
            requestParams,
            request,
            debugLines: buildDebugLines(),
          });
          return;
        }

        availableReports = reportsByType.get(selectedGroup.type) ?? [];

        if (requestParams.reportId) {
          selectedReport = availableReports.find((report) => report.id === requestParams.reportId);

          if (!selectedReport) {
            // -- Error: a non-existing report or one with insufficent access-rights was selected

            res.render('index', {
              availableGroups,
              groupOptions,
              availableReports,
              selectedGroup,
              selectedReport,
              requestParams,
              request,
              debugLines: buildDebugLines(),
            });
            return;
          }

          const parameter = await metadataService.getParameterFor(selectedReport.id);
          const parameterToFill = parameter.filter((p) => p.name !== 'p_gruppe_id' && p.name !== 'groupId');

          Object.keys(requestParams)
            .filter((key) => key.startsWith('p_'))
            .filter((key) => !parameter.some((p) => p.name === key.substring(2)))
            .forEach((key) => delete requestParams[key]);

          res.render('index', {
            availableGroups,
            groupOptions,
            availableReports,
            selectedGroup,
            selectedReport,
            parameterToFill,
            requestParams,
            request,
            debugLines: buildDebugLines(),
          });
          return;
        }
      }

      res.render('index', {
        availableGroups,
        groupOptions,
        availableReports,
        selectedGroup,
        selectedReport,
        requestParams,
        request,
        debugLines: buildDebugLines(),
      });
    } catch (err) {
      next(err);
    }
  });

  return router;
}
