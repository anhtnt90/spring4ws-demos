package ch.rasc.s4ws.wamp.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.springframework.core.GenericTypeResolver;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.messaging.handler.method.HandlerMethod;
import org.springframework.util.ReflectionUtils;

import ch.rasc.s4ws.wamp.message.BaseMessage;

public class InvocableHandlerMethod extends HandlerMethod {

	private HandlerMethodArgumentResolverComposite argumentResolvers = new HandlerMethodArgumentResolverComposite();

	private ParameterNameDiscoverer parameterNameDiscoverer = new LocalVariableTableParameterNameDiscoverer();

	/**
	 * Create an instance from a {@code HandlerMethod}.
	 */
	public InvocableHandlerMethod(HandlerMethod handlerMethod) {
		super(handlerMethod);
	}

	/**
	 * Create an instance from a bean instance and a method.
	 */
	public InvocableHandlerMethod(Object bean, Method method) {
		super(bean, method);
	}

	/**
	 * Constructs a new handler method with the given bean instance, method name
	 * and parameters.
	 * 
	 * @param bean the object bean
	 * @param methodName the method name
	 * @param parameterTypes the method parameter types
	 * @throws NoSuchMethodException when the method cannot be found
	 */
	public InvocableHandlerMethod(Object bean, String methodName, Class<?>... parameterTypes)
			throws NoSuchMethodException {

		super(bean, methodName, parameterTypes);
	}

	/**
	 * Set {@link HandlerMethodArgumentResolver}s to use to use for resolving
	 * method argument values.
	 */
	public void setMessageMethodArgumentResolvers(HandlerMethodArgumentResolverComposite argumentResolvers) {
		this.argumentResolvers = argumentResolvers;
	}

	/**
	 * Set the ParameterNameDiscoverer for resolving parameter names when needed
	 * (e.g. default request attribute name).
	 * <p>
	 * Default is an
	 * {@link org.springframework.core.LocalVariableTableParameterNameDiscoverer}
	 * instance.
	 */
	public void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Invoke the method with the given message.
	 * 
	 * @exception Exception raised if no suitable argument resolver can be
	 *                found, or the method raised an exception
	 */
	public final Object invoke(BaseMessage message, Object... providedArgs) throws Exception {

		Object[] args = getMethodArgumentValues(message, providedArgs);

		if (logger.isTraceEnabled()) {
			StringBuilder sb = new StringBuilder("Invoking [");
			sb.append(this.getBeanType().getSimpleName()).append(".");
			sb.append(this.getMethod().getName()).append("] method with arguments ");
			sb.append(Arrays.asList(args));
			logger.trace(sb.toString());
		}

		Object returnValue = invoke(args);

		if (logger.isTraceEnabled()) {
			logger.trace("Method returned [" + returnValue + "]");
		}

		return returnValue;
	}

	/**
	 * Get the method argument values for the current request.
	 */
	private Object[] getMethodArgumentValues(BaseMessage message, Object... providedArgs) throws Exception {

		MethodParameter[] parameters = getMethodParameters();
		Object[] args = new Object[parameters.length];
		for (int i = 0; i < parameters.length; i++) {
			MethodParameter parameter = parameters[i];
			parameter.initParameterNameDiscovery(parameterNameDiscoverer);
			GenericTypeResolver.resolveParameterType(parameter, getBean().getClass());

			args[i] = resolveProvidedArgument(parameter, providedArgs);
			if (args[i] != null) {
				continue;
			}

			if (this.argumentResolvers.supportsParameter(parameter)) {
				try {
					args[i] = this.argumentResolvers.resolveArgument(parameter, message);
					continue;
				} catch (Exception ex) {
					if (logger.isTraceEnabled()) {
						logger.trace(getArgumentResolutionErrorMessage("Error resolving argument", i), ex);
					}
					throw ex;
				}
			}

			if (args[i] == null) {
				String msg = getArgumentResolutionErrorMessage("No suitable resolver for argument", i);
				throw new IllegalStateException(msg);
			}
		}
		return args;
	}

	private String getArgumentResolutionErrorMessage(String message, int index) {
		MethodParameter param = getMethodParameters()[index];
		String errorMessage = message + " [" + index + "] [type=" + param.getParameterType().getName() + "]";
		return getDetailedErrorMessage(errorMessage);
	}

	/**
	 * Adds HandlerMethod details such as the controller type and method
	 * signature to the given error message.
	 * 
	 * @param message error message to append the HandlerMethod details to
	 */
	protected String getDetailedErrorMessage(String message) {
		StringBuilder sb = new StringBuilder(message).append("\n");
		sb.append("HandlerMethod details: \n");
		sb.append("Controller [").append(getBeanType().getName()).append("]\n");
		sb.append("Method [").append(getBridgedMethod().toGenericString()).append("]\n");
		return sb.toString();
	}

	/**
	 * Attempt to resolve a method parameter from the list of provided argument
	 * values.
	 */
	private static Object resolveProvidedArgument(MethodParameter parameter, Object... providedArgs) {
		if (providedArgs == null) {
			return null;
		}
		for (Object providedArg : providedArgs) {
			if (parameter.getParameterType().isInstance(providedArg)) {
				return providedArg;
			}
		}
		return null;
	}

	/**
	 * Invoke the handler method with the given argument values.
	 */
	private Object invoke(Object... args) throws Exception {
		ReflectionUtils.makeAccessible(this.getBridgedMethod());
		try {
			return getBridgedMethod().invoke(getBean(), args);
		} catch (IllegalArgumentException e) {
			String msg = getInvocationErrorMessage(e.getMessage(), args);
			throw new IllegalArgumentException(msg, e);
		} catch (InvocationTargetException e) {
			// Unwrap for HandlerExceptionResolvers ...
			Throwable targetException = e.getTargetException();
			if (targetException instanceof RuntimeException) {
				throw (RuntimeException) targetException;
			} else if (targetException instanceof Error) {
				throw (Error) targetException;
			} else if (targetException instanceof Exception) {
				throw (Exception) targetException;
			} else {
				String msg = getInvocationErrorMessage("Failed to invoke controller method", args);
				throw new IllegalStateException(msg, targetException);
			}
		}
	}

	private String getInvocationErrorMessage(String message, Object[] resolvedArgs) {
		StringBuilder sb = new StringBuilder(getDetailedErrorMessage(message));
		sb.append("Resolved arguments: \n");
		for (int i = 0; i < resolvedArgs.length; i++) {
			sb.append("[").append(i).append("] ");
			if (resolvedArgs[i] == null) {
				sb.append("[null] \n");
			} else {
				sb.append("[type=").append(resolvedArgs[i].getClass().getName()).append("] ");
				sb.append("[value=").append(resolvedArgs[i]).append("]\n");
			}
		}
		return sb.toString();
	}

}
