import { Request, Response, NextFunction, Router } from "express";
import { Report } from "../domain/model/report";
import { Principal } from "../domain/model/principal";
import { Group } from "../domain/model/group";
import { AppServices } from "../composition-root";
import { sortGroupsHierarchically } from "./group-hierarchy";

var express = require("express");

export function createIndexRouter(services: AppServices): Router {
  const router = express.Router();
  const { groupsService, metadataService } = services;

  /* GET home page. */
  router.get(
    "/",
    async function (req: Request, res: Response, next: NextFunction) {
      try {
        const requestParams = req.query;
        const request = {
          timestamp: new Date().toISOString(),
          url: req.originalUrl,
          method: req.method,
        };

        const principal = req.principal as Principal;
        res.locals.principal = principal;

        const availableGroups = await groupsService.findFor(principal);
        const groupOptions = sortGroupsHierarchically(availableGroups);

        let selectedGroup: Group | undefined = undefined;
        let availableReports: readonly Report[] | undefined = undefined;
        let selectedReport: Report | undefined = undefined;

        if (requestParams.groupId) {
          selectedGroup = availableGroups.find(
            (group) => group.id === requestParams.groupId,
          );

          if (!selectedGroup) {
            // -- Error: a non-existing group or one with insufficent access-rights was selected

            res.render("index", {
              availableGroups,
              groupOptions,
              availableReports,
              selectedGroup,
              selectedReport,
              requestParams,
              request,
            });
            return;
          }

          availableReports = await metadataService.findFor("*");

          if (requestParams.reportId) {
            selectedReport = availableReports.find(
              (report) => report.id === requestParams.reportId,
            );

            if (!selectedReport) {
              // -- Error: a non-existing report or one with insufficent access-rights was selected

              res.render("index", {
                availableGroups,
                groupOptions,
                availableReports,
                selectedGroup,
                selectedReport,
                requestParams,
                request,
              });
              return;
            }

            const parameter = await metadataService.getParameterFor(
              selectedReport.id,
            );
            const parameterToFill = parameter.filter(
              (p) => p.name !== "h_grpId" && p.name !== "groupId",
            );

            // cleanup unused request parameter from url
            Object.keys(requestParams)
              .filter((key) => key.startsWith("p_"))
              .filter(
                (key) =>
                  parameter.findIndex((p) => p.name === key.substring(3)) === 0,
              )
              .forEach((key) => delete requestParams.key);

            res.render("index", {
              availableGroups,
              groupOptions,
              availableReports,
              selectedGroup,
              selectedReport,
              parameterToFill,
              requestParams,
              request,
            });
            return;
          }
        }

        res.render("index", {
          availableGroups,
          groupOptions,
          availableReports,
          selectedGroup,
          selectedReport,
          requestParams,
          request,
        });
      } catch (err) {
        next(err);
      }
    },
  );

  return router;
}
