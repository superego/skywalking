/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.skywalking.apm.plugin.spring.cloud.gateway.v30x;

import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.adapter.DefaultServerWebExchange;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.net.URI;

import static org.apache.skywalking.apm.network.trace.component.ComponentsDefine.SPRING_CLOUD_GATEWAY;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.OK;

public class WebFilterInterceptor implements InstanceMethodsAroundInterceptor {

    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                             MethodInterceptResult result) throws Throwable {
        ServerWebExchange exchange = (ServerWebExchange) allArguments[0];
        EnhancedInstance enhancedInstance = getInstance(exchange);

        URI url = exchange.getRequest().getURI();
        ContextCarrier carrier = new ContextCarrier();
        AbstractSpan span = ContextManager.createExitSpan(getRequestURIString(url), carrier, getIPAndPort(url));

        span.setComponent(SPRING_CLOUD_GATEWAY);
        Tags.URL.set(span, url.toString());
        Tags.HTTP.METHOD.set(span, exchange.getRequest().getMethodValue());
        SpanLayer.asHttp(span);

        if (exchange instanceof EnhancedInstance) {
            ((EnhancedInstance) exchange).setSkyWalkingDynamicField(carrier);
        }

        //user async interface
        span.prepareForAsync();
        ContextManager.stopSpan();

        objInst.setSkyWalkingDynamicField(span);
    }

    public static EnhancedInstance getInstance(Object o) {
        EnhancedInstance instance = null;
        if (o instanceof DefaultServerWebExchange) {
            instance = (EnhancedInstance) o;
        } else if (o instanceof ServerWebExchangeDecorator) {
            ServerWebExchange delegate = ((ServerWebExchangeDecorator) o).getDelegate();
            return getInstance(delegate);
        }
        return instance;
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments, Class<?>[] argumentsTypes,
                              Object ret) throws Throwable {

        AbstractSpan span = (AbstractSpan) objInst.getSkyWalkingDynamicField();
        return ((Mono<?>) ret)
            .doOnSuccess(o -> Tags.STATUS_CODE.set(span, Integer.toString(OK.value())))
            .doOnError(t -> {
                Tags.STATUS_CODE.set(span, Integer.toString(INTERNAL_SERVER_ERROR.value()));
                span.errorOccurred();
                span.log(t);
            }).doFinally(s -> span.asyncFinish());
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
                                      Class<?>[] argumentsTypes, Throwable t) {
        ContextManager.activeSpan().log(t);
    }

    private String getRequestURIString(URI uri) {
        String requestPath = uri.getPath();
        return requestPath != null && requestPath.length() > 0 ? requestPath : "/";
    }

    private String getIPAndPort(URI uri) {
        return uri.getHost() + ":" + uri.getPort();
    }
}
