/*
 * Copyright 2010-2017 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.core.http.pipeline.stages;

import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.annotations.ReviewBeforeRelease;
import software.amazon.awssdk.core.RequestExecutionContext;
import software.amazon.awssdk.core.Response;
import software.amazon.awssdk.core.RetryableException;
import software.amazon.awssdk.core.SdkBaseException;
import software.amazon.awssdk.core.SdkClientException;
import software.amazon.awssdk.core.SdkStandardLoggers;
import software.amazon.awssdk.core.http.HttpResponse;
import software.amazon.awssdk.core.http.HttpResponseHandler;
import software.amazon.awssdk.core.http.pipeline.RequestPipeline;
import software.amazon.awssdk.utils.IoUtils;

/**
 * Unmarshalls an HTTP response into either a successful response POJO, or into a (possibly modeled) exception. Returns a wrapper
 * {@link Response} object which may contain either the unmarshalled success POJO, or the unmarshalled exception.
 *
 * @param <OutputT> Type of successful unmarshalled POJO.
 */
@ReviewBeforeRelease("Should this be broken up? It's doing quite a lot...")
public class HandleResponseStage<OutputT> implements RequestPipeline<HttpResponse, Response<OutputT>> {
    private static final Logger log = LoggerFactory.getLogger(HandleResponseStage.class);

    private final HttpResponseHandler<OutputT> successResponseHandler;
    private final HttpResponseHandler<? extends SdkBaseException> errorResponseHandler;

    public HandleResponseStage(HttpResponseHandler<OutputT> successResponseHandler,
                               HttpResponseHandler<? extends SdkBaseException> errorResponseHandler) {
        this.successResponseHandler = successResponseHandler;
        this.errorResponseHandler = errorResponseHandler;
    }

    @Override
    public Response<OutputT> execute(HttpResponse httpResponse, RequestExecutionContext context) throws Exception {
        boolean didRequestFail = true;
        try {
            Response<OutputT> response = handleResponse(httpResponse, context);
            didRequestFail = response.isFailure();
            return response;
        } finally {
            closeInputStreamIfNeeded(httpResponse, didRequestFail);
        }
    }

    private Response<OutputT> handleResponse(HttpResponse httpResponse,
                                             RequestExecutionContext context)
            throws IOException, InterruptedException {
        if (httpResponse.isSuccessful()) {
            OutputT response = handleSuccessResponse(httpResponse, context);
            return Response.fromSuccess(response, httpResponse);
        } else {
            return Response.fromFailure(handleErrorResponse(httpResponse, context), httpResponse);
        }
    }

    /**
     * Handles a successful response from a service call by unmarshalling the results using the
     * specified response handler.
     *
     * @return The contents of the response, unmarshalled using the specified response handler.
     * @throws IOException If any problems were encountered reading the response contents from
     *                     the HTTP method object.
     */
    @SuppressWarnings("deprecation")
    private OutputT handleSuccessResponse(HttpResponse httpResponse, RequestExecutionContext context)
            throws IOException, InterruptedException {
        try {
            return successResponseHandler.handle(httpResponse, context.executionAttributes());
        } catch (IOException | InterruptedException | RetryableException e) {
            throw e;
        } catch (Exception e) {
            String errorMessage =
                    "Unable to unmarshall response (" + e.getMessage() + "). Response Code: "
                    + httpResponse.getStatusCode() + ", Response Text: " + httpResponse.getStatusText();
            throw new SdkClientException(errorMessage, e);
        }
    }

    /**
     * Responsible for handling an error response, including unmarshalling the error response
     * into the most specific exception type possible, and throwing the exception.
     *
     * @throws IOException If any problems are encountering reading the error response.
     */
    private SdkBaseException handleErrorResponse(HttpResponse httpResponse,
                                                 RequestExecutionContext context)
            throws IOException, InterruptedException {
        try {
            SdkBaseException exception = errorResponseHandler.handle(httpResponse, context.executionAttributes());
            exception.fillInStackTrace();
            SdkStandardLoggers.REQUEST_LOGGER.debug(() -> "Received error response: " + exception);
            return exception;
        } catch (InterruptedException | IOException e) {
            throw e;
        } catch (Exception e) {
            String errorMessage = String.format("Unable to unmarshall error response (%s). " +
                                                "Response Code: %d, Response Text: %s", e.getMessage(),
                                                httpResponse.getStatusCode(), httpResponse.getStatusText());
            throw new SdkClientException(errorMessage, e);
        }
    }

    /**
     * Close the input stream if required.
     */
    private void closeInputStreamIfNeeded(HttpResponse httpResponse,
                                          boolean didRequestFail) {
        // Always close on failed requests. Close on successful unless streaming operation.
        if (didRequestFail || !successResponseHandler.needsConnectionLeftOpen()) {
            Optional.ofNullable(httpResponse)
                    .map(HttpResponse::getContent) // If no content, no need to close
                    .ifPresent(s -> IoUtils.closeQuietly(s, log));
        }
    }

}
