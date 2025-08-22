package com.example.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidateOrReject {
    String dlqDestination() default "";
    Class<?> expectedType() default Object.class;
    String messageIdHeader() default "JMSMessageID";
}
