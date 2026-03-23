#pragma once
#include <string>
#include <vector>
#include <functional>
#include <unordered_map>

namespace calc {

class Calculator {
public:
    // Basic arithmetic
    static double add(double a, double b);
    static double subtract(double a, double b);
    static double multiply(double a, double b);
    static double divide(double a, double b);
    static double modulo(double a, double b);

    // Power & roots
    static double power(double base, double exp);
    static double sqrt(double x);
    static double cbrt(double x);
    static double nthRoot(double x, double n);

    // Trigonometric (radians)
    static double sin(double x);
    static double cos(double x);
    static double tan(double x);
    static double asin(double x);
    static double acos(double x);
    static double atan(double x);
    static double atan2(double y, double x);

    // Hyperbolic
    static double sinh(double x);
    static double cosh(double x);
    static double tanh(double x);

    // Logarithmic
    static double ln(double x);
    static double log10(double x);
    static double log2(double x);
    static double logBase(double x, double base);

    // Other
    static double abs(double x);
    static double ceil(double x);
    static double floor(double x);
    static double round(double x);
    static double factorial(int n);
    static double permutation(int n, int r);
    static double combination(int n, int r);

    // Conversion
    static double degToRad(double deg);
    static double radToDeg(double rad);

    // Constants
    static double pi();
    static double e();

    // Expression evaluation
    static double evaluate(const std::string& expression);

    // List all available functions
    static std::vector<std::string> listFunctions();
};

// Recursive-descent expression parser
class ExpressionParser {
public:
    explicit ExpressionParser(const std::string& expr);
    double parse();

private:
    std::string expr_;
    size_t pos_;

    double parseExpression();
    double parseTerm();
    double parseUnary();
    double parsePower();
    double parseAtom();
    double parseFunction(const std::string& name);
    double parseNumber();

    char peek() const;
    char get();
    void skipWhitespace();
    bool match(char c);
};

} // namespace calc
