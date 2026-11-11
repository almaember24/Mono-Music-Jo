package com.prism.music

/**
 * A test that needs the real internet (it plays real audio or video, which the recordings don't hold). Left out of
 * normal runs (build.gradle.kts: notAnnotation); run them with tools/record.ps1 -live, or with
 * `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=org.junit.Ignore` and `record=true`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Live
