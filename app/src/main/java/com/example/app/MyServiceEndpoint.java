package com.example.app;

import com.example.sdk.ServiceEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MyServiceEndpoint implements ServiceEndpoint {

    private static final Logger log = LoggerFactory.getLogger(MyServiceEndpoint.class);

    @Override
    public String name() {
        return "MyServiceEndpoint";
    }

    @Override
    public void handle(String request) {
        log.info("Handling: {}", request);
        // DEBUG only appears if logback-app.xml was successfully included
        // (com.example.app logger set to DEBUG there)
        log.debug("DEBUG visible => logback-app.xml include worked. request={}", request);
    }
}
