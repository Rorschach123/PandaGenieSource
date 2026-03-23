package ai.rorsch.pandagenie.nativelib

class CalculatorLib {

    external fun add(a: Double, b: Double): Double
    external fun subtract(a: Double, b: Double): Double
    external fun multiply(a: Double, b: Double): Double
    external fun divide(a: Double, b: Double): Double
    external fun modulo(a: Double, b: Double): Double
    external fun power(base: Double, exp: Double): Double
    external fun nSqrt(x: Double): Double
    external fun cbrt(x: Double): Double
    external fun sin(x: Double): Double
    external fun cos(x: Double): Double
    external fun tan(x: Double): Double
    external fun asin(x: Double): Double
    external fun acos(x: Double): Double
    external fun atan(x: Double): Double
    external fun sinh(x: Double): Double
    external fun cosh(x: Double): Double
    external fun tanh(x: Double): Double
    external fun ln(x: Double): Double
    external fun log10(x: Double): Double
    external fun log2(x: Double): Double
    external fun factorial(n: Int): Double
    external fun permutation(n: Int, r: Int): Double
    external fun combination(n: Int, r: Int): Double
    external fun degToRad(deg: Double): Double
    external fun radToDeg(rad: Double): Double
    external fun evaluate(expression: String): Double
    external fun listFunctions(): Array<String>
}
