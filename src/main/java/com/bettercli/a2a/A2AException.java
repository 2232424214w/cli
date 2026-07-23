package com.bettercli.a2a;

/**
 * A2A 调用异常。
 */
public class A2AException extends RuntimeException {
    public A2AException(String msg) { super(msg); }
    public A2AException(String msg, Throwable cause) { super(msg, cause); }
}
