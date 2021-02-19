/******************************************************************************
 * Copyright (c) 2000-2021 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************/
package org.eclipse.titan.designer.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.titan.common.logging.ErrorReporter;
import org.eclipse.titan.common.path.PathConverter;
import org.eclipse.titan.common.utils.Cygwin;
import org.eclipse.titan.designer.actions.ExternalTitanAction;
import org.eclipse.titan.designer.consoles.TITANConsole;
import org.eclipse.titan.designer.consoles.TITANDebugConsole;
import org.eclipse.titan.designer.license.License;
import org.eclipse.titan.designer.license.LicenseValidator;
import org.eclipse.titan.designer.preferences.PreferenceConstants;
import org.eclipse.titan.designer.productUtilities.ProductConstants;

/**
 * Collects version information from the command line compiler.
 * 
 * @author Kristof Szabados
 * */
public final class CompilerVersionInformationCollector {
	private static final String TTCN3_LICENSE_FILE_KEY = "TTCN3_LICENSE_FILE";
	private static final String TTCN3_DIR_KEY = "TTCN3_DIR";
	private static final String LD_LIBRARY_PATH_KEY = "LD_LIBRARY_PATH";
	private static final String LIBRARY_SUB_DIR = "/lib";
	public static final String VERSION_CHECK_FLAG = "v";
	public static final String COMPILER_SUBPATH = File.separatorChar + "bin" + File.separatorChar + "compiler";
	private static final String SPACE = " ";
	private static final String SUCCESS = "Operation finished successfully.";
	private static final String FAILURE = "Operation failed with return value: ";

	private static final Pattern BASE_TITAN_HEADER_PATTERN = Pattern.compile(
			"TTCN\\-3 and ASN\\.1 Compiler for the TTCN\\-3 Test Executor, version (.+)\\n"
			+ "Product number: (.+)\\n"
			+ "Build date: (.+)\\n"
			+ "Compiled with: ([^\\n]+)\\n?\\n"
			+ "(.+)", Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern BASE_TITAN_HEADER_PATTERN2 = Pattern.compile(
			"TTCN\\-3 and ASN\\.1 Compiler for the TTCN\\-3 Test Executor\\n"
			+ "Product number: (.+)\\n"
			+ "Version: (.+)\\n"
			+ "Build date: (.+)\\n"
			+ "Compiled with: ([^\\n]+)\\n?\\n"
			+ "(.+)", Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern BASE_TITAN_HEADER_PATTERN3 = Pattern.compile(
			"TTCN\\-3 and ASN\\.1 Compiler for the TTCN\\-3 Test Executor\\n"
			+ "Product number: (.+)\\n"
			+ "Build date: (.+)\\n"
			+ "Compiled with: ([^\\n]+)\\n?\\n"
			+ "(.+)", Pattern.MULTILINE | Pattern.DOTALL);

	private static final Pattern BASE_TITAN_HEADER_PATTERN4 = Pattern.compile(
			"TTCN\\-3 and ASN\\.1 Compiler for the TTCN\\-3 Test Executor\\n"
			+ "Version: (.+)\\n"
			+ "Build date: (.+)\\n"
			+ "Compiled with: ([^\\n]+)\\n?\\n"
			+ "(.+)", Pattern.MULTILINE | Pattern.DOTALL);

	private static final class CompilerInfoStruct {
		private String compilerProductNumber;
		private String buildDate;
		private String cCompilerVersion; // version of the c++ compiler
		private String compilerVersion;

		private CompilerInfoStruct() {
		}
	}

	private static String resolvedInstallationPath = "";
	private static CompilerInfoStruct compilerInfoStruct;
	private static boolean infoIsUptodate = false;

	private CompilerVersionInformationCollector() {
	}

	/**
	 * @return a version string representing the version of the compiler in
	 *         Ericsson format, or null.
	 * */
	public static String getCompilerProductNumber() {
		if (!infoIsUptodate) {
			collectInformation();
		}

		if (compilerInfoStruct == null) {
			return null;
		}

		return compilerInfoStruct.compilerProductNumber;
	}

	/**
	 * @return the date and time of compilation as reported by the compiler,
	 *         or null.
	 * */
	public static String getBuildDate() {
		if (!infoIsUptodate) {
			collectInformation();
		}

		if (compilerInfoStruct == null) {
			return null;
		}

		return compilerInfoStruct.buildDate;
	}

	/**
	 * @return the version of GCC the compiler was compiled with, as
	 *         reported by the compiler, or null.
	 * */
	public static String getCCompilerVersion() {
		if (!infoIsUptodate) {
			collectInformation();
		}

		if (compilerInfoStruct == null) {
			return null;
		}

		return compilerInfoStruct.cCompilerVersion;
	}

	/**
	 * Sets the environmental variables needed to run.
	 * 
	 * @param pb
	 *                the process builder to set the calculated variables
	 *                on.
	 * @param path
	 *                the installation path of the TITAN toolset.
	 * */
	private static void setEnvironmentalVariables(final ProcessBuilder pb, final String path) {
		final Map<String, String> env = pb.environment();

		final boolean reportDebugInformation = Platform.getPreferencesService().getBoolean(ProductConstants.PRODUCT_ID_DESIGNER,
				PreferenceConstants.DISPLAYDEBUGINFORMATION, false, null);

		final StringBuilder output = new StringBuilder();
		final String ttcn3Dir = PathConverter.convert(path, reportDebugInformation, output);
		TITANDebugConsole.println(output);
		if( License.isLicenseNeeded() ) {
			env.put(TTCN3_LICENSE_FILE_KEY, LicenseValidator.getResolvedLicenseFilePath(false));
		}

		env.put(TTCN3_DIR_KEY, ttcn3Dir);
		final String temp = env.get(LD_LIBRARY_PATH_KEY);
		if (temp == null) {
			env.put(LD_LIBRARY_PATH_KEY, ttcn3Dir + LIBRARY_SUB_DIR);
		} else {
			env.put(LD_LIBRARY_PATH_KEY, temp + ":" + ttcn3Dir + LIBRARY_SUB_DIR);
		}
	}

	/**
	 * Does the actual work of collecting the version information from the
	 * provided path.
	 * 
	 * @param path
	 *                the path of the supposed TITAN installation.
	 * @return the structure recovered from the compiler, or null in case of
	 *         problems.
	 * */
	private static CompilerInfoStruct collectVersionInformation(final String path) {
		if (path == null || path.trim().length() == 0) {
			return null;
		}

		// If we are on win32 and we do not have cygwin -> cancel
		if (Platform.OS_WIN32.equals(Platform.getOS())) {
			if (!Cygwin.isInstalled()) {
				return null;
			}
		}

		final boolean reportDebugInformation = Platform.getPreferencesService().getBoolean(ProductConstants.PRODUCT_ID_DESIGNER,
				PreferenceConstants.DISPLAYDEBUGINFORMATION, false, null);

		final ArrayList<String> command = new ArrayList<String>();
		final StringBuilder output = new StringBuilder();
		command.add(PathConverter.convert(new Path(path + COMPILER_SUBPATH).toOSString(), reportDebugInformation, output));
		TITANDebugConsole.println(output);
		command.add('-' + VERSION_CHECK_FLAG);

		final ProcessBuilder pb = new ProcessBuilder();
		setEnvironmentalVariables(pb, path);
		pb.redirectErrorStream(true);
		Process proc = null;
		BufferedReader stdout;

		final StringBuilder tempCommand = new StringBuilder();
		for (final String c : command) {
			tempCommand.append(c).append(SPACE);
		}

		final ArrayList<String> finalCommand = new ArrayList<String>();
		finalCommand.add(ExternalTitanAction.SHELL);
		finalCommand.add("-c");
		finalCommand.add(tempCommand.toString());

		final StringBuilder readLines = new StringBuilder();

		pb.command(finalCommand);
		try {
			proc = pb.start();
		} catch (IOException e) {
			TITANConsole.println(ExternalTitanAction.EXECUTION_FAILED);
			ErrorReporter.logExceptionStackTrace(e);
			return null;
		}
		stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()));

		try {
			int linesRead = 0;
			String line = stdout.readLine();
			while (line != null) {
				linesRead++;
				if (linesRead < 7) {
					readLines.append(line).append('\n');
				} else if (linesRead > 100) {
					return null;
				}
				if (reportDebugInformation) {
					TITANConsole.println(line);
				}
				line = stdout.readLine();
			}

			final int exitval = proc.waitFor();
			if (exitval != 0) {
				if (reportDebugInformation) {
					TITANConsole.println(FAILURE + exitval);
				}
				proc.destroy();
				return null;
			}

			if (reportDebugInformation) {
				TITANConsole.println(SUCCESS);
			}
		} catch (IOException e) {
			ErrorReporter.logExceptionStackTrace(e);
		} catch (InterruptedException e) {
			ErrorReporter.logExceptionStackTrace(e);
		} finally {
			try {
				stdout.close();
			} catch (IOException e) {
				ErrorReporter.logExceptionStackTrace(e);
			}
		}

		final Matcher baseTITANErrorMatcher = BASE_TITAN_HEADER_PATTERN.matcher(readLines.toString());
		if (baseTITANErrorMatcher.matches()) {
			final CompilerInfoStruct temp = new CompilerInfoStruct();
			temp.compilerVersion = baseTITANErrorMatcher.group(1);//TODO: display
			temp.compilerProductNumber = baseTITANErrorMatcher.group(2);
			temp.buildDate = baseTITANErrorMatcher.group(3);
			temp.cCompilerVersion = baseTITANErrorMatcher.group(4);

			return temp;
		}

		final Matcher baseTITANErrorMatcher2 = BASE_TITAN_HEADER_PATTERN2.matcher(readLines.toString());
		if (baseTITANErrorMatcher2.matches()) {
			final CompilerInfoStruct temp = new CompilerInfoStruct();
			temp.compilerProductNumber = baseTITANErrorMatcher2.group(1);
			temp.compilerVersion = baseTITANErrorMatcher2.group(2); //TODO: display
			temp.buildDate = baseTITANErrorMatcher2.group(3);
			temp.cCompilerVersion = baseTITANErrorMatcher2.group(4);

			return temp;
		}

		final Matcher baseTITANErrorMatcher3 = BASE_TITAN_HEADER_PATTERN3.matcher(readLines.toString());
		if (baseTITANErrorMatcher3.matches()) {
			final CompilerInfoStruct temp = new CompilerInfoStruct();
			temp.compilerProductNumber = baseTITANErrorMatcher3.group(1);
			temp.buildDate = baseTITANErrorMatcher3.group(2);
			temp.cCompilerVersion = baseTITANErrorMatcher3.group(3);
			temp.compilerVersion = null;

			return temp;
		}

		final Matcher baseTITANErrorMatcher4 = BASE_TITAN_HEADER_PATTERN4.matcher(readLines.toString());
		if (baseTITANErrorMatcher3.matches()) {
			final CompilerInfoStruct temp = new CompilerInfoStruct();
			temp.compilerProductNumber = "";
			temp.compilerVersion = baseTITANErrorMatcher4.group(1);
			temp.buildDate = baseTITANErrorMatcher4.group(2);
			temp.cCompilerVersion = baseTITANErrorMatcher4.group(3);

			return temp;
		}
		return null;
	}

	/**
	 * Calculates and returns the path of the TITAN installation resolved to
	 * be directly usable in the actual environment.
	 * 
	 * @param force
	 *                weather to update the value if it already exist or
	 *                not.
	 * @return the resolved installation path
	 * */
	public static String getResolvedInstallationPath(final boolean force) {
		final boolean reportDebugInformation = Platform.getPreferencesService().getBoolean(ProductConstants.PRODUCT_ID_DESIGNER,
				PreferenceConstants.DISPLAYDEBUGINFORMATION, false, null);

		if (force || resolvedInstallationPath.length() == 0) {
			final String installationPath = Platform.getPreferencesService().getString(ProductConstants.PRODUCT_ID_DESIGNER,
					PreferenceConstants.TITAN_INSTALLATION_PATH, "", null);
			final StringBuilder output = new StringBuilder();
			resolvedInstallationPath = PathConverter.convert(installationPath, reportDebugInformation, output);
			TITANDebugConsole.println(output);
		}

		return resolvedInstallationPath;
	}

	/**
	 * Checks the location provided as TITAN installation path, in order to
	 * determine the version of the compiler installed there.
	 * 
	 * @param path
	 *                the path to check.
	 * @return the version string if found, or null.
	 * */
	public static String checkTemporalLocation(final String path) {
		if (path == null) {
			return null;
		}

		final CompilerInfoStruct temp = collectVersionInformation(path);
		if (temp == null) {
			return null;
		}

		return temp.compilerProductNumber;
	}

	/**
	 * Collect all of the information from the environment, to determine the
	 * version of the installed compiler.
	 * */
	public static void collectInformation() {
		if (compilerInfoStruct != null || infoIsUptodate) {
			return;
		}

		final String installationPath = Platform.getPreferencesService().getString(ProductConstants.PRODUCT_ID_DESIGNER,
				PreferenceConstants.TITAN_INSTALLATION_PATH, null, null);
		compilerInfoStruct = collectVersionInformation(installationPath);
		infoIsUptodate = true;
	}

	/**
	 * Clear all stored information that is related to the actual settings.
	 * */
	public static void clearStoredInformation() {
		compilerInfoStruct = null;
		resolvedInstallationPath = "";
		infoIsUptodate = false;
	}
}
