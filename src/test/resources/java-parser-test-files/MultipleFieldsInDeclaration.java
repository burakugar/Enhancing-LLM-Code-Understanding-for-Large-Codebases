package com.example.test;

public class MultipleFieldsInDeclaration {
    // Multiple fields in one declaration
    @SuppressWarnings("unused")
    private int alpha, beta = 20, gamma; // gamma has no initializer

    String name = "default", value;

    public static final double PI = 3.14159, E = 2.71828;
} 