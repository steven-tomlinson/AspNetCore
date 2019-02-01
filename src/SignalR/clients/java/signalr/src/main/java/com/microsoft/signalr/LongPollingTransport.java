// Copyright (c) .NET Foundation. All rights reserved.
// Licensed under the Apache License, Version 2.0. See License.txt in the project root for license information.

package com.microsoft.signalr;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.subjects.CompletableSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

class LongPollingTransport implements Transport {
    private OnReceiveCallBack onReceiveCallBack;
    private TransportOnClosedCallback onClose;
    private String url;
    private final HttpClient client;
    private final HttpClient pollingClient;
    private final Map<String, String> headers;
    private static final int POLL_TIMEOUT = 100*1000;
    private volatile Boolean active;
    private String pollUrl;
    private final Logger logger = LoggerFactory.getLogger(LongPollingTransport.class);

    public LongPollingTransport(Map<String, String> headers, HttpClient client) {
        this.headers = headers;
        this.client = client;
        this.pollingClient = client.cloneWithTimeOut(POLL_TIMEOUT);
    }

    @Override
    public Completable start(String url) {
        this.active = true;
        logger.info("Starting LongPolling transport");
        this.url = url;
        pollUrl = url + "&_=" + System.currentTimeMillis();
        logger.info("Polling {}", pollUrl);
        HttpRequest request = new HttpRequest();
        request.addHeaders(headers);
        return this.pollingClient.get(pollUrl, request).flatMapCompletable(response -> {
            if (response.getStatusCode() != 200){
                logger.error("Unexpected response code {}", response.getStatusCode());
                this.active = false;
                return Completable.error(new Exception("Failed to connect"));
            } else {
                logger.info("Activating poll loop", response.getStatusCode());
                this.active = true;
            }

            new Thread(() -> poll(url)).start();

            return Completable.complete();
        });
    }

    private Completable poll(String url){
        if (this.active) {
            pollUrl = url + "&_=" + System.currentTimeMillis();
            logger.info("Polling {}", pollUrl);
            HttpRequest request = new HttpRequest();
            request.addHeaders(headers);
            CompletableSubject poll = CompletableSubject.create();
            this.pollingClient.get(pollUrl, request).flatMapCompletable(response -> {
                if (response.getStatusCode() == 204) {
                    logger.info("LongPolling transport terminated by server.");
                    this.active = false;
                } else if (response.getStatusCode() != 200) {
                    logger.error("Unexpected response code {}", response.getStatusCode());
                    this.active = false;
                } else {
                    logger.info("Message received");
                    new Thread(() -> this.onReceive(response.getContent())).start();
                }
                return poll(url); }).subscribeWith(poll);
        }
        return Completable.complete();
    }

    @Override
    public Completable send(String message) {
        return Completable.fromSingle(this.client.post(url, message));
    }

    @Override
    public void setOnReceive(OnReceiveCallBack callback) {
        this.onReceiveCallBack = callback;
    }

    @Override
    public void onReceive(String message) {
        this.onReceiveCallBack.invoke(message);
        logger.debug("OnReceived callback has been invoked.");
    }

    @Override
    public void setOnClose(TransportOnClosedCallback onCloseCallback) {
        this.onClose = onCloseCallback;
    }

    @Override
    public Completable stop() {
        logger.info("LongPolling transport stopped.");
        this.active = false;
        this.client.delete(this.url);
        this.onClose.invoke(null);
        return Completable.complete();
    }
}
