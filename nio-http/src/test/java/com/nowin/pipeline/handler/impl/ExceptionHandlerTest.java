package com.nowin.pipeline.handler.impl;

import com.nowin.exception.InvalidRequestException;
import com.nowin.pipeline.ChannelHandlerContext;
import com.nowin.pipeline.ChannelPipeline;
import com.nowin.pipeline.handler.ChannelHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 创建一个简单的ChannelHandler实现，用于测试
class TestHandler implements ChannelHandler {
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // do nothing
    }

    @Override
    public void channelWrite(ChannelHandlerContext ctx, Object msg) {
        // do nothing
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // do nothing
    }
}

class ExceptionHandlerTest {

    private ExceptionHandler exceptionHandler;
    private ChannelHandlerContext context;

    @BeforeEach
    void setUp() {
        exceptionHandler = new ExceptionHandler();
        
        // 创建测试所需的组件
        ChannelPipeline pipeline = new ChannelPipeline();
        ChannelHandler testHandler = new TestHandler();
        context = new ChannelHandlerContext("test", pipeline, testHandler);
    }

    @Test
    void testExceptionHandlingDoesNotThrow() {
        // 测试各种异常类型的处理，确保不会抛出异常
        
        // 测试InvalidRequestException
        exceptionHandler.exceptionCaught(context, new InvalidRequestException("Invalid parameter"));
        
        // 测试IllegalArgumentException
        exceptionHandler.exceptionCaught(context, new IllegalArgumentException("Illegal argument"));
        
        // 测试NullPointerException
        exceptionHandler.exceptionCaught(context, new NullPointerException("Null pointer"));
        
        // 测试ArrayIndexOutOfBoundsException
        exceptionHandler.exceptionCaught(context, new ArrayIndexOutOfBoundsException("Array index out of bounds"));
        
        // 测试RuntimeException
        exceptionHandler.exceptionCaught(context, new RuntimeException("Runtime exception"));
        
        // 测试通用Exception
        exceptionHandler.exceptionCaught(context, new Exception("Generic exception"));
        
        // 测试Throwable
        exceptionHandler.exceptionCaught(context, new Throwable("Generic throwable"));
    }
}
