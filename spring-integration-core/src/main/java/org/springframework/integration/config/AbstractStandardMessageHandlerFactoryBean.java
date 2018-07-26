/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.config;

import java.util.HashSet;
import java.util.Set;

import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.handler.AbstractMessageProducingHandler;
import org.springframework.integration.handler.AbstractReplyProducingMessageHandler;
import org.springframework.integration.handler.MessageProcessor;
import org.springframework.messaging.MessageHandler;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Base class for FactoryBeans that create standard MessageHandler instances.
 *
 * @author Mark Fisher
 * @author Alexander Peters
 * @author Gary Russell
 * @author Artem Bilan
 * @author David Liu
 */
public abstract class AbstractStandardMessageHandlerFactoryBean
		extends AbstractSimpleMessageHandlerFactoryBean<MessageHandler> implements DisposableBean {

	private static final ExpressionParser expressionParser = new SpelExpressionParser();

	private static final Set<MessageHandler> referencedReplyProducers = new HashSet<>();

	private Boolean requiresReply;

	private Object targetObject;

	private String targetMethodName;

	private Expression expression;

	private Long sendTimeout;

	private MessageHandler replyHandler;

	/**
	 * Set the target POJO for the message handler.
	 * @param targetObject the target object.
	 */
	public void setTargetObject(Object targetObject) {
		this.targetObject = targetObject;
	}

	/**
	 * Set the method name for the message handler.
	 * @param targetMethodName the target method name.
	 */
	public void setTargetMethodName(String targetMethodName) {
		this.targetMethodName = targetMethodName;
	}

	/**
	 * Set a SpEL expression to use.
	 * @param expressionString the expression as a String.
	 */
	public void setExpressionString(String expressionString) {
		this.expression = expressionParser.parseExpression(expressionString);
	}

	/**
	 * Set a SpEL expression to use.
	 * @param expression the expression.
	 */
	public void setExpression(Expression expression) {
		this.expression = expression;
	}

	public void setRequiresReply(Boolean requiresReply) {
		this.requiresReply = requiresReply;
	}

	public void setSendTimeout(Long sendTimeout) {
		this.sendTimeout = sendTimeout;
	}

	public Long getSendTimeout() {
		return this.sendTimeout;
	}

	@Override
	public void destroy() {
		if (this.replyHandler != null) {
			referencedReplyProducers.remove(this.replyHandler);
		}
	}

	@Override
	protected MessageHandler createHandler() {
		MessageHandler handler;
		if (this.targetObject == null) {
			Assert.isTrue(!StringUtils.hasText(this.targetMethodName),
					"The target method is only allowed when a target object (ref or inner bean) is also provided.");
		}
		if (this.targetObject != null) {
			Assert.state(this.expression == null,
					"The 'targetObject' and 'expression' properties are mutually exclusive.");
			AbstractMessageProducingHandler actualHandler = this.extractTypeIfPossible(this.targetObject,
					AbstractMessageProducingHandler.class);
			boolean targetIsDirectReplyProducingHandler = actualHandler != null
					&& canBeUsedDirect(actualHandler) // give subclasses a say
					&& methodIsHandleMessageOrEmpty(this.targetMethodName);
			if (this.targetObject instanceof MessageProcessor<?>) {
				handler = this.createMessageProcessingHandler((MessageProcessor<?>) this.targetObject);
			}
			else if (targetIsDirectReplyProducingHandler) {
				if (logger.isDebugEnabled()) {
					logger.debug("Wiring handler (" + this.targetObject + ") directly into endpoint");
				}
				checkReuse(actualHandler);
				postProcessReplyProducer(actualHandler);
				handler = (MessageHandler) this.targetObject;
			}
			else {
				handler = this.createMethodInvokingHandler(this.targetObject, this.targetMethodName);
			}
		}
		else if (this.expression != null) {
			handler = this.createExpressionEvaluatingHandler(this.expression);
		}
		else {
			handler = this.createDefaultHandler();
		}
		return handler;
	}

	protected void checkForIllegalTarget(Object targetObject, String targetMethodName) {
		if (targetObject instanceof AbstractReplyProducingMessageHandler
				&& this.methodIsHandleMessageOrEmpty(targetMethodName)) {
			/*
			 * If we allow an ARPMH to be the target of another ARPMH, the reply would
			 * be attempted to be sent by the inner (no output channel) and a reply would
			 * never be received by the outer (fails if replyRequired).
			 */
			throw new IllegalArgumentException("AbstractReplyProducingMessageHandler.handleMessage() "
					+ "is not allowed for a MethodInvokingHandler");
		}
	}

	private void checkReuse(AbstractMessageProducingHandler replyHandler) {
		Assert.isTrue(!referencedReplyProducers.contains(replyHandler),
				"An AbstractMessageProducingMessageHandler may only be referenced once (" +
						replyHandler.getComponentName() + ") - use scope=\"prototype\"");
		referencedReplyProducers.add(replyHandler);
		this.replyHandler = replyHandler;
	}

	/**
	 * Subclasses must implement this method to create the MessageHandler.
	 * @param targetObject the object to use for method invocation.
	 * @param targetMethodName the method name of the target object to invoke.
	 * @return the method invoking {@link MessageHandler} implementation.
	 */
	protected abstract MessageHandler createMethodInvokingHandler(Object targetObject, String targetMethodName);

	protected MessageHandler createExpressionEvaluatingHandler(Expression expression) {
		throw new UnsupportedOperationException(this.getClass().getName() + " does not support expressions.");
	}

	protected <T> MessageHandler createMessageProcessingHandler(MessageProcessor<T> processor) {
		return this.createMethodInvokingHandler(processor, null);
	}

	protected MessageHandler createDefaultHandler() {
		throw new IllegalArgumentException("Exactly one of the 'targetObject' or 'expression' property is required.");
	}

	@SuppressWarnings("unchecked")
	protected <T> T extractTypeIfPossible(Object targetObject, Class<T> expectedType) {
		if (targetObject == null) {
			return null;
		}
		if (expectedType.isAssignableFrom(targetObject.getClass())) {
			return (T) targetObject;
		}
		if (targetObject instanceof Advised) {
			TargetSource targetSource = ((Advised) targetObject).getTargetSource();
			try {
				return extractTypeIfPossible(targetSource.getTarget(), expectedType);
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
		return null;
	}

	protected boolean methodIsHandleMessageOrEmpty(String targetMethodName) {
		return (!StringUtils.hasText(targetMethodName)
				|| "handleMessage".equals(targetMethodName));
	}

	protected boolean canBeUsedDirect(AbstractMessageProducingHandler handler) {
		return false;
	}

	protected void postProcessReplyProducer(AbstractMessageProducingHandler handler) {
		if (this.sendTimeout != null) {
			handler.setSendTimeout(this.sendTimeout);
		}

		if (this.requiresReply != null) {
			if (handler instanceof AbstractReplyProducingMessageHandler) {
				((AbstractReplyProducingMessageHandler) handler).setRequiresReply(this.requiresReply);
			}
			else {
				if (this.requiresReply && logger.isDebugEnabled()) {
					logger.debug("requires-reply can only be set to AbstractReplyProducingMessageHandler " +
							"or its subclass, " + handler.getComponentName() + " doesn't support it.");
				}
			}
		}
	}

}
