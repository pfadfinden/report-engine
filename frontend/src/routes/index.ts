import { Request, Response } from "express";
import { Report } from "../domain/model/report";
import { LocalMetadataLoaderService } from "../domain/adapter/local-metadata-loader.service";
import { Principal } from "../domain/model/principal";
import { PrincipalOnlyGroupsService } from "../domain/adapter/principal-only-groups.service";
import { Group } from "../domain/model/group";
import { RemoteMetadataLoaderService } from "../domain/adapter/remote-metadata-loader.service";

var express = require('express');
var router = express.Router();

/* GET home page. */
router.get('/', async function (req: Request, res: Response) {
  const requestParams = req.query;
  const request = { timestamp: new Date().toISOString(), url: req.originalUrl, method: req.method }

  // TODO retrieve principal from token
  const principal: Principal = {
    id: 'ulid',
    name: 'Jane Doe'
  }

  // TODO inject services
  const availableGroups = await new PrincipalOnlyGroupsService().findFor(principal)
  //const metadataService = await new LocalMetadataLoaderService().load('../dist/preprocessed/reports.db');
  const metadataService = await new RemoteMetadataLoaderService(null, 30 * 60 * 1000, 'github_pat_8_8').load('https://github.com/pfadfinden/mv_reports/releases/download/v0.0.1/reports.db');


  let selectedGroup: Group | undefined = undefined;
  let availableReports: readonly Report[] | undefined = undefined;
  let selectedReport: Report | undefined = undefined;

  if (requestParams.groupId) {
    selectedGroup = availableGroups.find(group => group.id === requestParams.groupId);

    if (!selectedGroup) {
      // -- Error: a non-existing group or one with insufficent access-rights was selected

      res.render('index', {
        availableGroups,
        availableReports,
        selectedGroup,
        selectedReport,
        requestParams,
        request
      });
      return;
    }

    availableReports = await metadataService.findFor('*');

    if (requestParams.reportId) {
      selectedReport = availableReports.find(report => report.id === requestParams.reportId);

      if (!selectedReport) {
        // -- Error: a non-existing report or one with insufficent access-rights was selected

        res.render('index', {
          availableGroups,
          availableReports,
          selectedGroup,
          selectedReport,
          requestParams,
          request
        });
        return;
      }

      const parameter = await metadataService.getParameterFor(selectedReport.id);
      const parameterToFill = parameter.filter(p => p.name !== 'h_grpId' && p.name !== 'groupId');

      // cleanup unused request parameter from url
      Object.keys(requestParams)
        .filter(key => key.startsWith('p_'))
        .filter(key => parameter.findIndex(p => p.name === key.substring(3)) === 0)
        .forEach(key => delete requestParams.key)

      res.render('index', {
        availableGroups,
        availableReports,
        selectedGroup,
        selectedReport,
        parameterToFill,
        requestParams,
        request
      });
      return;
    }
  }

  res.render('index', {
    availableGroups,
    availableReports,
    selectedGroup,
    selectedReport,
    requestParams,
    request
  });
});

module.exports = router;
