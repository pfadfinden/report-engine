#!/usr/bin/env node

/**
 * Module dependencies.
 */

import type { Server } from 'http';

require('dotenv').config({ quiet: true });

var shutdownTelemetry = require('../telemetry/telemetry').initTelemetry();

var logger = require('../telemetry/logger');
var http = require('http');
var { loadConfig } = require('../config');
var { createServices } = require('../composition-root');
var { createApp } = require('../express-app');

/**
 * Normalize a port into a number, string, or false.
 */

function normalizePort(val: string): number | string | false {
  var port = parseInt(val, 10);

  if (isNaN(port)) {
    // named pipe
    return val;
  }

  if (port >= 0) {
    // port number
    return port;
  }

  return false;
}

/**
 * Event listener for HTTP server "error" event.
 */

function onError(port: number | string | false) {
  return function (error: NodeJS.ErrnoException) {
    if (error.syscall !== 'listen') {
      throw error;
    }

    var bind = typeof port === 'string' ? 'Pipe ' + port : 'Port ' + port;

    // handle specific listen errors with friendly messages
    switch (error.code) {
      case 'EACCES':
        logger.error('server.listen.failed', { bind }, error);
        process.exit(1);
        break;
      case 'EADDRINUSE':
        logger.error('server.listen.failed', { bind }, error);
        process.exit(1);
        break;
      default:
        throw error;
    }
  };
}

/**
 * Event listener for HTTP server "listening" event.
 */

function onListening(server: Server) {
  return function () {
    var addr = server.address();
    var bind = typeof addr === 'string' ? 'pipe ' + addr : 'port ' + addr?.port;
    logger.event('server.listening', { bind });
  };
}

/**
 * Stop accepting new connections, let in-flight requests finish, flush telemetry, then exit.
 * Registered for SIGTERM/SIGINT so a rolling deploy or Ctrl+C doesn't drop requests mid-flight.
 */
function shutdown(server: Server) {
  let shuttingDown = false;

  return function (signal: NodeJS.Signals) {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;

    logger.event('server.shutdown.started', { signal });

    const forceExit = setTimeout(() => {
      logger.error('server.shutdown.timed_out', {}, new Error('graceful shutdown timed out, forcing exit'));
      process.exit(1);
    }, 10000);
    forceExit.unref();

    server.close(async (err) => {
      if (err) {
        logger.error('server.shutdown.failed', {}, err);
      }
      await shutdownTelemetry();
      process.exit(err ? 1 : 0);
    });
  };
}

async function main() {
  const config = loadConfig();

  /**
   * Get port from environment and store in Express.
   */
  var port = normalizePort(String(config.port));

  const services = await createServices(config);
  var app = createApp(services, config);
  app.set('port', port);

  /**
   * Create HTTP server.
   */
  var server = http.createServer(app);

  /**
   * Listen on provided port, on all network interfaces.
   */
  server.listen(port);
  server.on('error', onError(port));
  server.on('listening', onListening(server));

  var handleShutdown = shutdown(server);
  process.on('SIGTERM', handleShutdown);
  process.on('SIGINT', handleShutdown);
}

main().catch(function (err) {
  logger.error('server.startup.failed', {}, err);
  process.exit(1);
});
