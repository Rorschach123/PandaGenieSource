package ai.rorsch.pandagenie.nativelib;

public class CalculatorLib {

    static {
        System.loadLibrary("calculator");
    }

    public native double add(double a, double b);
    public native double subtract(double a, double b);
    public native double multiply(double a, double b);
    public native double divide(double a, double b);
    public native double modulo(double a, double b);
    public native double power(double base, double exp);
    public native double nSqrt(double x);
    public native double cbrt(double x);
    public native double sin(double x);
    public native double cos(double x);
    public native double tan(double x);
    public native double asin(double x);
    public native double acos(double x);
    public native double atan(double x);
    public native double sinh(double x);
    public native double cosh(double x);
    public native double tanh(double x);
    public native double ln(double x);
    public native double log10(double x);
    public native double log2(double x);
    public native double factorial(int n);
    public native double permutation(int n, int r);
    public native double combination(int n, int r);
    public native double degToRad(double deg);
    public native double radToDeg(double rad);
    public native double evaluate(String expression);
    public native String[] listFunctions();
}
