package com.jetbrains.lang.dart.ide.runner.test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Parse, decode, and convert JSON objects generated by the test runner into internal test events.
public class DartTestJsonReader {

  private static final String TYPE_START = "start";
  private static final String TYPE_ERROR = "error";
  private static final String TYPE_PRINT = "print";
  private static final String TYPE_PASS = "pass";
  private static final String TYPE_FAIL = "fail";
  private static final String TYPE_SKIP = "skip";
  private static final String TYPE_EXIT = "exit";
  private static final String TYPE_ENTER = "enter";

  private static final String JSON_TYPE = "type";
  private static final String JSON_NAME = "name";
  private static final String JSON_MILLIS = "time";
  private static final String JSON_MESSAGE = "message";
  private static final String JSON_ERROR_MESSAGE = "errorMessage";
  private static final String JSON_FAIL_MESSAGE = "failMessage";
  private static final String JSON_STACK_TRACE = "stackTrace";
  private static final String JSON_REASON = "reason";
  private static final String JSON_COUNT = "count";

  private static final String NEWLINE = "\n";
  private static final String OBSERVATORY_MSG = "Observatory listening on";
  private static final String EXPECTED = "Expected: ";
  private static final Pattern EXPECTED_ACTUAL_RESULT = Pattern.compile("\\nExpected: (.*)\\n  Actual: (.*)\\n *\\^\\n Differ.*\\n");

  private DartTestSignaller myProcessor;
  private int myTestId = 0;
  // In theory, test events could be generated asynchronously and out of order. We might want to keep a map of tests to start times
  // so we get accurate durations when tests end. We need a unique identifier for tests to do that, so it is overkill for now.
  private long startMillis;

  public DartTestJsonReader(DartTestSignaller processor) {
    myProcessor = processor;
  }

  public boolean process(final String text, final Key contentType) throws JsonSyntaxException {
    JsonParser jp = new JsonParser();
    JsonElement elem;
    try {
      elem = jp.parse(text);
    }
    catch (JsonSyntaxException ex) {
      if (text.startsWith(OBSERVATORY_MSG) && text.endsWith(NEWLINE)) {
        myProcessor.signalTestFrameworkAttached();
      }
      throw ex;
    }
    if (elem == null || !elem.isJsonObject()) return false;
    process(elem.getAsJsonObject());
    return true;
  }

  private void process(JsonObject obj) throws JsonSyntaxException {
    String type = obj.get(JSON_TYPE).getAsString();
    if (TYPE_START.equals(type)) {
      processStart(obj);
    }
    else if (TYPE_ERROR.equals(type)) {
      processError(obj);
    }
    else if (TYPE_PASS.equals(type)) {
      processPass(obj);
    }
    else if (TYPE_FAIL.equals(type)) {
      processFail(obj);
    }
    else if (TYPE_SKIP.equals(type)) {
      processSkip(obj);
    }
    else if (TYPE_EXIT.equals(type)) {
      processExit(obj);
    }
    else if (TYPE_PRINT.equals(type)) {
      processPrint(obj);
    }
    else if (TYPE_ENTER.equals(type)) {
      processEnter(obj);
    }
    else {
      throw new JsonSyntaxException("Unexpected type: " + type + " (check for SDK update)");
    }
  }

  private void processStart(JsonObject obj) {
    myProcessor.signalTestStarted(testName(obj), ++myTestId, 0, null, null, null, true);
    startMillis = testMillis(obj);
  }

  private void processError(JsonObject obj) {
    long duration = testMillis(obj) - startMillis;
    myProcessor.signalTestFailure(testName(obj), myTestId, errorMessage(obj), stackTrace(obj), true, null, null, null, duration);
  }

  private void processPass(JsonObject obj) {
    long duration = testMillis(obj) - startMillis;
    myProcessor.signalTestFinished(testName(obj), myTestId, duration);
  }

  private void processFail(JsonObject obj) {
    long duration = testMillis(obj) - startMillis;
    String message = failMessage(obj);
    String expectedText = null, actualText = null, failureMessage = message;
    int firstExpectedIndex = message.indexOf(EXPECTED);
    if (firstExpectedIndex >= 0) {
      Matcher matcher = EXPECTED_ACTUAL_RESULT.matcher(message);
      if (matcher.find(firstExpectedIndex + EXPECTED.length())) {
        int matchEnd = matcher.end();
        expectedText = matcher.group(1);
        actualText = matcher.group(2);
        failureMessage = message.substring(0, firstExpectedIndex);
      }
    }
    // The stack trace could be null, but we disallow that for consistency with all the transmitted values.
    myProcessor
      .signalTestFailure(testName(obj), myTestId, failureMessage, stackTrace(obj), false, actualText, expectedText, null, duration);
  }

  private void processSkip(JsonObject obj) {
    myProcessor.signalTestSkipped(testName(obj), skipReason(obj), null);
  }

  private void processPrint(JsonObject obj) {
    myProcessor.signalTestMessage(testName(obj), myTestId, message(obj));
  }

  private void processEnter(JsonObject obj) {
    myProcessor.signalTestFrameworkAttached();
  }

  private void processExit(JsonObject obj) {
    // Tests are done.
  }

  private static long testMillis(JsonObject obj) {
    JsonElement val = obj.get(JSON_MILLIS);
    if (val == null || !val.isJsonPrimitive()) return 0L;
    return val.getAsLong();
  }

  @NotNull
  private static String testName(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_NAME, "<no name>");
  }

  @NotNull
  private static String failMessage(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_FAIL_MESSAGE, "<no fail message>");
  }

  @NotNull
  private static String errorMessage(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_ERROR_MESSAGE, "<no error message>");
  }

  @NotNull
  private static String message(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_MESSAGE, "<no message>");
  }

  @NotNull
  private static String stackTrace(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_STACK_TRACE, "<no stack trace>");
  }

  @NotNull
  private static String skipReason(JsonObject obj) {
    return nonNullJsonValue(obj, JSON_REASON, "<no skip reason>");
  }

  @NotNull
  private static String nonNullJsonValue(@NotNull JsonObject obj, @NotNull String id, @NotNull String def) {
    JsonElement val = obj.get(id);
    if (val == null || !val.isJsonPrimitive()) return def;
    return val.getAsString();
  }
}
