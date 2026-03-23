#include <jni.h>
#include <string>
#include "calculator.h"

#define JNI_METHOD(ret, name) \
    extern "C" JNIEXPORT ret JNICALL Java_ai_rorsch_pandagenie_nativelib_CalculatorLib_##name

JNI_METHOD(jdouble, add)(JNIEnv*, jobject, jdouble a, jdouble b) {
    return calc::Calculator::add(a, b);
}
JNI_METHOD(jdouble, subtract)(JNIEnv*, jobject, jdouble a, jdouble b) {
    return calc::Calculator::subtract(a, b);
}
JNI_METHOD(jdouble, multiply)(JNIEnv*, jobject, jdouble a, jdouble b) {
    return calc::Calculator::multiply(a, b);
}
JNI_METHOD(jdouble, divide)(JNIEnv* env, jobject, jdouble a, jdouble b) {
    try { return calc::Calculator::divide(a, b); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, modulo)(JNIEnv* env, jobject, jdouble a, jdouble b) {
    try { return calc::Calculator::modulo(a, b); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, power)(JNIEnv*, jobject, jdouble base, jdouble exp) {
    return calc::Calculator::power(base, exp);
}
JNI_METHOD(jdouble, nSqrt)(JNIEnv* env, jobject, jdouble x) {
    try { return calc::Calculator::sqrt(x); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, cbrt)(JNIEnv*, jobject, jdouble x) {
    return calc::Calculator::cbrt(x);
}
JNI_METHOD(jdouble, sin)(JNIEnv*, jobject, jdouble x) { return calc::Calculator::sin(x); }
JNI_METHOD(jdouble, cos)(JNIEnv*, jobject, jdouble x) { return calc::Calculator::cos(x); }
JNI_METHOD(jdouble, tan)(JNIEnv*, jobject, jdouble x) { return calc::Calculator::tan(x); }
JNI_METHOD(jdouble, asin)(JNIEnv* env, jobject, jdouble x) {
    try { return calc::Calculator::asin(x); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, acos)(JNIEnv* env, jobject, jdouble x) {
    try { return calc::Calculator::acos(x); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, atan)(JNIEnv*, jobject, jdouble x) { return calc::Calculator::atan(x); }
JNI_METHOD(jdouble, sinh)(JNIEnv*, jobject, jdouble x) { return calc::Calculator::sinh(x); }
JNI_METHOD(jdouble, cosh)(JNIEnv*, jobject, jdouble x) { return calc::Calculator::cosh(x); }
JNI_METHOD(jdouble, tanh)(JNIEnv*, jobject, jdouble x) { return calc::Calculator::tanh(x); }
JNI_METHOD(jdouble, ln)(JNIEnv* env, jobject, jdouble x) {
    try { return calc::Calculator::ln(x); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, log10)(JNIEnv* env, jobject, jdouble x) {
    try { return calc::Calculator::log10(x); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, log2)(JNIEnv* env, jobject, jdouble x) {
    try { return calc::Calculator::log2(x); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, factorial)(JNIEnv* env, jobject, jint n) {
    try { return calc::Calculator::factorial(n); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, permutation)(JNIEnv* env, jobject, jint n, jint r) {
    try { return calc::Calculator::permutation(n, r); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, combination)(JNIEnv* env, jobject, jint n, jint r) {
    try { return calc::Calculator::combination(n, r); }
    catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/lang/ArithmeticException"), e.what()); return 0; }
}
JNI_METHOD(jdouble, degToRad)(JNIEnv*, jobject, jdouble deg) { return calc::Calculator::degToRad(deg); }
JNI_METHOD(jdouble, radToDeg)(JNIEnv*, jobject, jdouble rad) { return calc::Calculator::radToDeg(rad); }

JNI_METHOD(jdouble, evaluate)(JNIEnv* env, jobject, jstring expr) {
    const char* str = env->GetStringUTFChars(expr, nullptr);
    std::string expression(str);
    env->ReleaseStringUTFChars(expr, str);
    try {
        return calc::Calculator::evaluate(expression);
    } catch (const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return 0;
    }
}

JNI_METHOD(jobjectArray, listFunctions)(JNIEnv* env, jobject) {
    auto funcs = calc::Calculator::listFunctions();
    jobjectArray arr = env->NewObjectArray(funcs.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < funcs.size(); i++) {
        env->SetObjectArrayElement(arr, i, env->NewStringUTF(funcs[i].c_str()));
    }
    return arr;
}
