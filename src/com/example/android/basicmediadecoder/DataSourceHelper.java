package com.example.android.basicmediadecoder;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;


public abstract class DataSourceHelper implements InvocationHandler {
	
	public abstract int readAt( long offset, byte[] buffer, int size );
	public abstract long getSize();
	
	@Override
	public Object invoke(Object proxy, Method method, Object[] args)
			throws Throwable {
		String methodName = method.getName();
		if ("readAt".equals(methodName)) {
			return readAt( (Long)args[0], (byte[])args[1], (Integer)args[2] );
		} else if ("getSize".equals(methodName)) {
			return getSize();
		}
		return null;
	}		
}
