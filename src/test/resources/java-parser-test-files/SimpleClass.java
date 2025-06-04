package com.example.test;

import java.util.List;

/**
 * A simple class for testing the Java parser.
 */
@Deprecated
public class SimpleClass {
    private int count = 0;

    /**
     * Returns a message with the given name.
     *
     * @param name The name to include in the message
     * @return A greeting message
     */
    public String getMessage(String name) {
        return "Hello, " + name + "! Count is " + count;
    }

    // Static initializer block
    static {
        System.out.println("Static initializer");
    }

    // Instance initializer block
    {
        System.out.println("Instance initializer");
        count = 10;
    }

    /**
     * A nested static class
     */
    public static class NestedStaticClass {
        private String value;

        public String getValue() {
            return value;
        }
    }

    /**
     * An inner class
     */
    public class InnerClass {
        private String data;

        public String getData() {
            return data;
        }
    }
}

/**
 * A simple interface
 */
interface SimpleInterface {
    void doSomething();
    String getValue();
}

/**
 * A simple enum
 */
enum SimpleEnum {
    A, B, C;
}

/**
 * A simple annotation
 */
@interface SimpleAnnotation {
    String value() default "";
}
