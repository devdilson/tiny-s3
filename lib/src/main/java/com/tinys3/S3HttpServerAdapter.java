package com.tinys3;

import com.sun.net.httpserver.HttpHandler;

public interface S3HttpServerAdapter {

  HttpHandler getHandler();
}
