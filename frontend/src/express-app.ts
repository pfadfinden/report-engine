import { Request, Response, NextFunction } from "express";
import { AppServices } from "./composition-root";
import { AppConfig } from "./config";
import { createIndexRouter } from "./routes/index";
import { createReportExecutionRouter } from "./routes/report-execution";
import { createAuthRouter } from "./auth/auth-router";
import { requireAuth } from "./auth/require-auth";

var createError = require("http-errors");
var express = require("express");
var path = require("path");
var logger = require("morgan");
var session = require("express-session");

export function createApp(services: AppServices, config: AppConfig) {
  var app = express();

  // view engine setup
  app.set("views", path.join(__dirname, "templates"));
  app.set("view engine", "pug");

  app.use(logger(app.get("env") === "production" ? "combined" : "dev"));
  app.use(express.json());
  app.use(express.urlencoded({ extended: false }));
  app.use(express.static(path.join(__dirname, "public")));

  app.get("/healthz", function (req: Request, res: Response) {
    res.status(200).json({ status: "ok" });
  });

  // MemoryStore is fine for a single instance; swap for a shared store (e.g. redis) before scaling out
  app.use(
    session({
      secret: config.auth.sessionSecret,
      resave: false,
      saveUninitialized: false,
      cookie: {
        httpOnly: true,
        sameSite: "lax",
        secure: config.nodeEnv === "production",
      },
    }),
  );

  app.use(
    "/auth",
    createAuthRouter(
      services.authClient,
      config.auth,
      config.groups.hitobitoApiUrl,
    ),
  );

  app.use("/", requireAuth, createReportExecutionRouter(services, config));
  app.use("/", requireAuth, createIndexRouter(services));

  // catch 404 and forward to error handler
  app.use(function (req: Request, res: Response, next: NextFunction) {
    next(createError(404));
  });

  // error handler
  app.use(function (
    err: { message: string; status: number },
    req: Request,
    res: Response,
    next: NextFunction,
  ) {
    // set locals, only providing error in development
    res.locals.message = err.message;
    res.locals.error = req.app.get("env") === "development" ? err : {};

    // render the error page
    res.status(err.status || 500);
    res.render("error");
  });

  return app;
}
