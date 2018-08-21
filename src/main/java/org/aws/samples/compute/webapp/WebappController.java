package org.aws.samples.compute.webapp;

import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.HttpResponse;
import org.apache.http.impl.client.CloseableHttpClient;
import com.amazonaws.xray.proxies.apache.http.HttpClientBuilder;
import org.apache.http.client.config.RequestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.WebApplicationException;

import java.util.Random;

@Path("/")
public class WebappController {

    private static final Logger logger = LoggerFactory.getLogger(WebappController.class);

    @Produces(MediaType.TEXT_PLAIN)
    @GET
    public String getMessage(@Context UriInfo uri) {
        String greetingEndpoint = getEndpoint("GREETING", uri.getRequestUri().getScheme(), null);

        // Randomize ID when none is set. More useful upstream responses
        Random rand = new Random();
        String id = Integer.toString(rand.nextInt(8) + 1);
        String pathQuery = ("/" + id);

        String nameEndpoint = getEndpoint("NAME", uri.getRequestUri().getScheme(), pathQuery);

        // Set strict timeout, so we see slowly responding calls as well.
        int timeout = 6;
        RequestConfig config = RequestConfig.custom()
          .setConnectTimeout(timeout * 1000)
          .setConnectionRequestTimeout(timeout * 1000)
          .setSocketTimeout(timeout * 1000).build();
        CloseableHttpClient httpclient =
          HttpClientBuilder.create().setDefaultRequestConfig(config).build();

        //Add AWS X-Ray tracing capabilities to the client.
        Unirest.setHttpClient(httpclient);

        String greetingMessage = "";
        try {
            HttpResponse<String> textResponse = Unirest
                    .get(greetingEndpoint)
                    .header("accept", "text/plain")
                    .asString();

            if (textResponse.getStatus() != 200)
            {
              // Throw exception with upstream status code
              // AWS X-Ray will capture this and show it in the map
              throw new WebApplicationException(textResponse.getStatus());
            }

            greetingMessage = textResponse.getBody();
            logger.info("Greeting is: " + greetingMessage);
        } catch (Exception e) {
            logger.error("Failed connecting Greeting API: " + e);
            throw new ServiceUnavailableException("Greeting service not available", 30L);
        }

        String nameMessage = "";
        try {
            HttpResponse<String> textResponse = Unirest
                    .get(nameEndpoint)
                    .header("accept", "text/plain")
                    .asString();

            if (textResponse.getStatus() != 200)
            {
              // Throw exception with upstream status code
              // AWS X-Ray will capture this and show it in the map
              throw new WebApplicationException(textResponse.getStatus());
            }

            nameMessage = textResponse.getBody();
            logger.info("Name is: " + nameMessage);
        } catch (Exception e) {
            logger.error("Failed connecting Name API: " + e);
            throw new ServiceUnavailableException("Name service not available", 30L);
        }

        String lambdaEndpoint = System.getenv("LAMBDA_TRACKER");
        try {
            HttpResponse<String> textResponse = Unirest
                    .get(lambdaEndpoint)
                    .header("accept", "text/plain")
                    .queryString("username", nameMessage)
                    .queryString("message", greetingMessage)
                    .asString();

            /* Ignore status of Lambda response */
            if (textResponse.getStatus() != 200)
            {
              logger.info("API Gateway Error response: " + textResponse.getStatusText());
            } else {
                logger.info("API Gateway: " + textResponse.getBody());
            }

        } catch (Exception e) {
            logger.error("Failed connecting Tracking API: " + e);

            throw new ServiceUnavailableException("Tracking service not available", 30L);
        }

        return greetingMessage + " " + nameMessage;
    }

    private String getEndpoint(String type, String scheme, String pathQuery) {
        logger.info("getEndpoint: " + pathQuery);
        String host = System.getenv(type + "_SERVICE_HOST");
        if (null == host) {
            throw new RuntimeException(type + "_SERVICE_HOST environment variable not found");
        }

        String port = System.getenv(type + "_SERVICE_PORT");
        if (null == port) {
            throw new RuntimeException(type + "_SERVICE_PORT environment variable not found");
        }

        String path = System.getenv(type + "_SERVICE_PATH");
        if (null == path) {
            throw new RuntimeException(type + "_SERVICE_PATH environment variable not found");
        }
        if (null != pathQuery) {
            path = path + pathQuery;
        }
        logger.info("pathQuery: " + pathQuery);
        logger.info("path: " + path);

        /**
         * Note: Due to AWS Serverless Java Container assume all requests to API Gateway
         * are using HTTPS, so it hardcoded context URL to use "https". This assumption
         * doesn't work in SAM local. TODO: create an issue in AWS Serverless Java Container Github Repo
         */
        String schemeOverride = System.getenv(type + "_SERVICE_SCHEME");
        logger.info("scheme override is: " + schemeOverride);
        String endpoint;
        if (null == schemeOverride) {
            endpoint = scheme + "://" + host + ":" + port + path;
        } else {
            endpoint = schemeOverride + "://" + host + ":" + port + path;
        }

        logger.info(type + " endpoint: " + endpoint);
        return endpoint;
    }

}
