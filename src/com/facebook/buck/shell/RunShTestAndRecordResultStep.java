/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.shell;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

public class RunShTestAndRecordResultStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path pathToShellScript;
  private final ImmutableList<String> args;
  private final ImmutableMap<String, String> env;
  private final Path pathToTestResultFile;
  private final Optional<Long> testRuleTimeoutMs;
  private final String testCaseName;

  public RunShTestAndRecordResultStep(
      ProjectFilesystem filesystem,
      Path pathToShellScript,
      ImmutableList<String> args,
      ImmutableMap<String, String> env,
      Optional<Long> testRuleTimeoutMs,
      String testCaseName,
      Path pathToTestResultFile) {
    this.filesystem = filesystem;
    this.pathToShellScript = pathToShellScript;
    this.args = args;
    this.env = env;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.testCaseName = testCaseName;
    this.pathToTestResultFile = pathToTestResultFile;
  }

  @Override
  public String getShortName() {
    return pathToShellScript.toString();
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return pathToShellScript.toString();
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    TestResultSummary summary;
    if (context.getPlatform() == Platform.WINDOWS) {
      // Ignore sh_test on Windows.
      summary = new TestResultSummary(
          getShortName(),
          "sh_test",
          /* type */ ResultType.SUCCESS,
          /* duration*/ 0,
          /* message */ "sh_test ignored on Windows",
          /* stacktrace */ null,
          /* stdout */ null,
          /* stderr */ null);
    } else {
      ShellStep test = new ShellStep(filesystem.getRootPath()) {
        boolean timedOut = false;

        @Override
        public String getShortName() {
          return pathToShellScript.toString();
        }

        @Override
        protected ImmutableList<String> getShellCommandInternal(
            ExecutionContext context) {
          return ImmutableList.<String>builder()
              .add(pathToShellScript.toString())
              .addAll(args)
              .build();
        }

        @Override
        public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
          return ImmutableMap.<String, String>builder()
              .put("NO_BUCKD", "1")
              .putAll(env)
              .build();
        }

        @Override
        protected boolean shouldPrintStderr(Verbosity verbosity) {
          // Do not stream this output because we want to capture it.
          return false;
        }

        @Override
        public int execute(ExecutionContext context) throws InterruptedException {
          int exitCode = super.execute(context);
          if (timedOut) {
            throw new HumanReadableException(
                "Timed out running test: " + testCaseName + ", with exitCode: " + exitCode);
          }
          return exitCode;
        }

        @Override
        protected Optional<Function<Process, Void>> getTimeoutHandler(
            final ExecutionContext context) {
          return Optional.<Function<Process, Void>>of(
              new Function<Process, Void>() {
                @Override
                public Void apply(Process process) {
                  timedOut = true;
                  return null;
                }
              });
        }

        @Override
        protected Optional<Long> getTimeout() {
          return testRuleTimeoutMs;
        }

        @Override
        protected boolean shouldPrintStdout(Verbosity verbosity) {
          // Do not stream this output because we want to capture it.
          return false;
        }
      };
      int exitCode = test.execute(context);

      // Write test result.
      boolean isSuccess = exitCode == 0;
      summary = new TestResultSummary(
          getShortName(),
          "sh_test",
          /* type */ isSuccess ? ResultType.SUCCESS : ResultType.FAILURE,
          test.getDuration(),
          /* message */ null,
          /* stacktrace */ null,
          test.getStdout(),
          test.getStderr());
    }

    ObjectMapper mapper = context.getObjectMapper();
    try (OutputStream outputStream = filesystem.newFileOutputStream(pathToTestResultFile)) {
      mapper.writeValue(outputStream, summary);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Even though the test may have failed, this command executed successfully, so its exit code
    // should be zero.
    return 0;
  }

}
