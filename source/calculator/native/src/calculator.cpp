#include "calculator.h"
#include <cmath>
#include <stdexcept>
#include <cctype>
#include <algorithm>

namespace calc {

// --- Basic arithmetic ---
double Calculator::add(double a, double b) { return a + b; }
double Calculator::subtract(double a, double b) { return a - b; }
double Calculator::multiply(double a, double b) { return a * b; }
double Calculator::divide(double a, double b) {
    if (b == 0.0) throw std::runtime_error("Division by zero");
    return a / b;
}
double Calculator::modulo(double a, double b) {
    if (b == 0.0) throw std::runtime_error("Modulo by zero");
    return std::fmod(a, b);
}

// --- Power & roots ---
double Calculator::power(double base, double exp) { return std::pow(base, exp); }
double Calculator::sqrt(double x) {
    if (x < 0) throw std::runtime_error("sqrt of negative number");
    return std::sqrt(x);
}
double Calculator::cbrt(double x) { return std::cbrt(x); }
double Calculator::nthRoot(double x, double n) {
    if (n == 0) throw std::runtime_error("0th root undefined");
    if (x < 0 && std::fmod(n, 2.0) == 0)
        throw std::runtime_error("Even root of negative number");
    if (x < 0) return -std::pow(-x, 1.0 / n);
    return std::pow(x, 1.0 / n);
}

// --- Trigonometric ---
double Calculator::sin(double x) { return std::sin(x); }
double Calculator::cos(double x) { return std::cos(x); }
double Calculator::tan(double x) { return std::tan(x); }
double Calculator::asin(double x) {
    if (x < -1 || x > 1) throw std::runtime_error("asin domain error");
    return std::asin(x);
}
double Calculator::acos(double x) {
    if (x < -1 || x > 1) throw std::runtime_error("acos domain error");
    return std::acos(x);
}
double Calculator::atan(double x) { return std::atan(x); }
double Calculator::atan2(double y, double x) { return std::atan2(y, x); }

// --- Hyperbolic ---
double Calculator::sinh(double x) { return std::sinh(x); }
double Calculator::cosh(double x) { return std::cosh(x); }
double Calculator::tanh(double x) { return std::tanh(x); }

// --- Logarithmic ---
double Calculator::ln(double x) {
    if (x <= 0) throw std::runtime_error("ln of non-positive number");
    return std::log(x);
}
double Calculator::log10(double x) {
    if (x <= 0) throw std::runtime_error("log10 of non-positive number");
    return std::log10(x);
}
double Calculator::log2(double x) {
    if (x <= 0) throw std::runtime_error("log2 of non-positive number");
    return std::log2(x);
}
double Calculator::logBase(double x, double base) {
    if (x <= 0 || base <= 0 || base == 1)
        throw std::runtime_error("logBase domain error");
    return std::log(x) / std::log(base);
}

// --- Other ---
double Calculator::abs(double x) { return std::fabs(x); }
double Calculator::ceil(double x) { return std::ceil(x); }
double Calculator::floor(double x) { return std::floor(x); }
double Calculator::round(double x) { return std::round(x); }

double Calculator::factorial(int n) {
    if (n < 0) throw std::runtime_error("Factorial of negative number");
    if (n > 170) throw std::runtime_error("Factorial overflow");
    double result = 1.0;
    for (int i = 2; i <= n; ++i) result *= i;
    return result;
}

double Calculator::permutation(int n, int r) {
    if (n < 0 || r < 0 || r > n)
        throw std::runtime_error("Invalid permutation parameters");
    double result = 1.0;
    for (int i = n; i > n - r; --i) result *= i;
    return result;
}

double Calculator::combination(int n, int r) {
    if (n < 0 || r < 0 || r > n)
        throw std::runtime_error("Invalid combination parameters");
    if (r > n - r) r = n - r;
    double result = 1.0;
    for (int i = 0; i < r; ++i) {
        result *= (n - i);
        result /= (i + 1);
    }
    return result;
}

double Calculator::degToRad(double deg) { return deg * M_PI / 180.0; }
double Calculator::radToDeg(double rad) { return rad * 180.0 / M_PI; }
double Calculator::pi() { return M_PI; }
double Calculator::e() { return M_E; }

double Calculator::evaluate(const std::string& expression) {
    ExpressionParser parser(expression);
    return parser.parse();
}

std::vector<std::string> Calculator::listFunctions() {
    return {
        "add", "subtract", "multiply", "divide", "modulo",
        "power", "sqrt", "cbrt", "nthRoot",
        "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
        "sinh", "cosh", "tanh",
        "ln", "log10", "log2", "logBase",
        "abs", "ceil", "floor", "round",
        "factorial", "permutation", "combination",
        "degToRad", "radToDeg",
        "evaluate"
    };
}

// --- Expression Parser ---
ExpressionParser::ExpressionParser(const std::string& expr) : expr_(expr), pos_(0) {}

double ExpressionParser::parse() {
    skipWhitespace();
    double result = parseExpression();
    skipWhitespace();
    if (pos_ < expr_.size())
        throw std::runtime_error("Unexpected character: " + std::string(1, expr_[pos_]));
    return result;
}

// expression = term (('+' | '-') term)*
double ExpressionParser::parseExpression() {
    double result = parseTerm();
    while (true) {
        skipWhitespace();
        if (match('+')) result += parseTerm();
        else if (match('-')) result -= parseTerm();
        else break;
    }
    return result;
}

// term = power (('*' | '/' | '%') power)*
double ExpressionParser::parseTerm() {
    double result = parseUnary();
    while (true) {
        skipWhitespace();
        if (match('*')) result *= parseUnary();
        else if (match('/')) {
            double d = parseUnary();
            if (d == 0) throw std::runtime_error("Division by zero");
            result /= d;
        }
        else if (match('%')) {
            double d = parseUnary();
            if (d == 0) throw std::runtime_error("Modulo by zero");
            result = std::fmod(result, d);
        }
        else break;
    }
    return result;
}

// unary = ('+' | '-')? power
double ExpressionParser::parseUnary() {
    skipWhitespace();
    if (match('+')) return parsePower();
    if (match('-')) return -parsePower();
    return parsePower();
}

// power = atom ('^' unary)?
double ExpressionParser::parsePower() {
    double result = parseAtom();
    skipWhitespace();
    if (match('^')) {
        double exp = parseUnary();
        result = std::pow(result, exp);
    }
    return result;
}

// atom = number | '(' expression ')' | function '(' args ')' | constant
double ExpressionParser::parseAtom() {
    skipWhitespace();

    if (match('(')) {
        double result = parseExpression();
        skipWhitespace();
        if (!match(')')) throw std::runtime_error("Missing closing parenthesis");
        return result;
    }

    if (std::isalpha(peek()) || peek() == '_') {
        std::string name;
        while (pos_ < expr_.size() && (std::isalnum(expr_[pos_]) || expr_[pos_] == '_')) {
            name += expr_[pos_++];
        }
        std::transform(name.begin(), name.end(), name.begin(), ::tolower);

        if (name == "pi") return M_PI;
        if (name == "e") return M_E;

        skipWhitespace();
        if (match('(')) {
            return parseFunction(name);
        }
        throw std::runtime_error("Unknown identifier: " + name);
    }

    return parseNumber();
}

double ExpressionParser::parseFunction(const std::string& name) {
    std::vector<double> args;
    skipWhitespace();
    if (peek() != ')') {
        args.push_back(parseExpression());
        while (match(',')) {
            args.push_back(parseExpression());
        }
    }
    skipWhitespace();
    if (!match(')')) throw std::runtime_error("Missing closing parenthesis for function");

    if (name == "sin" && args.size() == 1) return std::sin(args[0]);
    if (name == "cos" && args.size() == 1) return std::cos(args[0]);
    if (name == "tan" && args.size() == 1) return std::tan(args[0]);
    if (name == "asin" && args.size() == 1) return std::asin(args[0]);
    if (name == "acos" && args.size() == 1) return std::acos(args[0]);
    if (name == "atan" && args.size() == 1) return std::atan(args[0]);
    if (name == "atan2" && args.size() == 2) return std::atan2(args[0], args[1]);
    if (name == "sinh" && args.size() == 1) return std::sinh(args[0]);
    if (name == "cosh" && args.size() == 1) return std::cosh(args[0]);
    if (name == "tanh" && args.size() == 1) return std::tanh(args[0]);
    if (name == "sqrt" && args.size() == 1) return Calculator::sqrt(args[0]);
    if (name == "cbrt" && args.size() == 1) return std::cbrt(args[0]);
    if (name == "abs" && args.size() == 1) return std::fabs(args[0]);
    if (name == "ceil" && args.size() == 1) return std::ceil(args[0]);
    if (name == "floor" && args.size() == 1) return std::floor(args[0]);
    if (name == "round" && args.size() == 1) return std::round(args[0]);
    if (name == "ln" && args.size() == 1) return Calculator::ln(args[0]);
    if (name == "log" && args.size() == 1) return Calculator::log10(args[0]);
    if (name == "log10" && args.size() == 1) return Calculator::log10(args[0]);
    if (name == "log2" && args.size() == 1) return Calculator::log2(args[0]);
    if ((name == "pow" || name == "power") && args.size() == 2) return std::pow(args[0], args[1]);
    if (name == "mod" && args.size() == 2) return Calculator::modulo(args[0], args[1]);
    if (name == "max" && args.size() == 2) return std::fmax(args[0], args[1]);
    if (name == "min" && args.size() == 2) return std::fmin(args[0], args[1]);
    if (name == "deg" && args.size() == 1) return Calculator::degToRad(args[0]);
    if (name == "rad" && args.size() == 1) return Calculator::radToDeg(args[0]);
    if (name == "fact" && args.size() == 1) return Calculator::factorial(static_cast<int>(args[0]));
    if (name == "perm" && args.size() == 2)
        return Calculator::permutation(static_cast<int>(args[0]), static_cast<int>(args[1]));
    if (name == "comb" && args.size() == 2)
        return Calculator::combination(static_cast<int>(args[0]), static_cast<int>(args[1]));

    throw std::runtime_error("Unknown function: " + name);
}

double ExpressionParser::parseNumber() {
    skipWhitespace();
    size_t start = pos_;
    while (pos_ < expr_.size() && (std::isdigit(expr_[pos_]) || expr_[pos_] == '.')) {
        pos_++;
    }
    if (pos_ == start) throw std::runtime_error("Expected number at position " + std::to_string(pos_));
    return std::stod(expr_.substr(start, pos_ - start));
}

char ExpressionParser::peek() const {
    return pos_ < expr_.size() ? expr_[pos_] : '\0';
}

char ExpressionParser::get() {
    return pos_ < expr_.size() ? expr_[pos_++] : '\0';
}

void ExpressionParser::skipWhitespace() {
    while (pos_ < expr_.size() && std::isspace(expr_[pos_])) pos_++;
}

bool ExpressionParser::match(char c) {
    skipWhitespace();
    if (pos_ < expr_.size() && expr_[pos_] == c) {
        pos_++;
        return true;
    }
    return false;
}

} // namespace calc
