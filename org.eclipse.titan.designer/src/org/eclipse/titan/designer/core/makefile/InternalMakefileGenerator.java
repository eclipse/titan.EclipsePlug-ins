/******************************************************************************
 * Copyright (c) 2000-2021 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************/
package org.eclipse.titan.designer.core.makefile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.titan.common.logging.ErrorReporter;
import org.eclipse.titan.common.path.PathConverter;
import org.eclipse.titan.common.path.PathUtil;
import org.eclipse.titan.common.path.TITANPathUtilities;
import org.eclipse.titan.common.product.ProductIdentity;
import org.eclipse.titan.common.utils.CommentUtils;
import org.eclipse.titan.common.utils.Cygwin;
import org.eclipse.titan.common.utils.IOUtils;
import org.eclipse.titan.common.utils.ResourceUtils;
import org.eclipse.titan.designer.GeneralConstants;
import org.eclipse.titan.designer.AST.Identifier;
import org.eclipse.titan.designer.AST.Identifier.Identifier_type;
import org.eclipse.titan.designer.consoles.TITANConsole;
import org.eclipse.titan.designer.consoles.TITANDebugConsole;
import org.eclipse.titan.designer.core.CompilerVersionInformationCollector;
import org.eclipse.titan.designer.core.LoadBalancingUtilities;
import org.eclipse.titan.designer.core.ProductIdentityHelper;
import org.eclipse.titan.designer.core.ProjectBasedBuilder;
import org.eclipse.titan.designer.parsers.GlobalParser;
import org.eclipse.titan.designer.parsers.ProjectSourceParser;
import org.eclipse.titan.designer.preferences.PreferenceConstants;
import org.eclipse.titan.designer.productUtilities.ProductConstants;
import org.eclipse.titan.designer.properties.data.CCompilerOptionsData;
import org.eclipse.titan.designer.properties.data.COptimalizationOptionsData;
import org.eclipse.titan.designer.properties.data.LinkerFlagsOptionsData;
import org.eclipse.titan.designer.properties.data.LinkerLibrariesOptionsData;
import org.eclipse.titan.designer.properties.data.MakefileCreationData;
import org.eclipse.titan.designer.properties.data.PlatformSpecificLibrariesOptionsData;
import org.eclipse.titan.designer.properties.data.PreprocessorIncludedOptionsData;
import org.eclipse.titan.designer.properties.data.PreprocessorSymbolsOptionsData;
import org.eclipse.titan.designer.properties.data.ProjectBuildPropertyData;
import org.eclipse.titan.designer.properties.data.TITANFlagsOptionsData;
import org.eclipse.titan.designer.properties.data.TTCN3PreprocessorOptionsData;

/**
 * This class is used to generate the Makefile used by the build system of Titan.
 * 
 * This variant of the Makefile generator is able to write the Makefile directly from eclipse,
 *   while having access to all eclipse resources and data from the on-he-fly analyzer.
 *
 * @author Kristof Szabados
 * @author Jeno Balasko
 * @author Arpad Lovassy
 * @author Szabo Bence Janos
 */
//check out IWorkspaceRoot.findFilesForLocation
public final class InternalMakefileGenerator {
	private static final String INVALID_OPTIONS = "Invalid build options found, for more information please refer to the Error Log view";
	private static final boolean USAGE_STATS = true;
	private static final String MISSING_CYGWIN = "Makefile generation failed. No cygwin installation found! Please make sure that cygwin is installed properly.";

	private final IProject project;
	// The OS specific path of the project, used to speed up calculations.
	private String projectLocation;

	private final List<ModuleStruct> ttcn3Modules = new ArrayList<ModuleStruct>();
	private boolean preprocess = false;
	private final List<ModuleStruct> ttcnppModules = new ArrayList<ModuleStruct>();
	private boolean ttcn3ModulesRegular = true;
	private boolean baseTTCN3ModulesRegular = true;
	private final List<TTCN3IncludeFileStruct> ttcn3IncludeFiles = new ArrayList<TTCN3IncludeFileStruct>();
	private final List<ModuleStruct> asn1modules = new ArrayList<ModuleStruct>();
	private boolean asn1ModulesRegular = true;
	private boolean baseASN1ModulesRegular = true;
	private final List<UserStruct> userFiles = new ArrayList<UserStruct>();
	private boolean userHeadersRegular = true;
	private boolean userSourcesRegular = true;
	private boolean baseUserHeadersRegular = true;
	private boolean baseUserSourcesRegular = true;
	private final List<OtherFileStruct> otherFiles = new ArrayList<OtherFileStruct>();
	private final List<BaseDirectoryStruct> baseDirectories = new ArrayList<BaseDirectoryStruct>();
	private final List<BaseDirectoryStruct> additionallyIncludedFolders = new ArrayList<BaseDirectoryStruct>();
	private String workingDirectory;
	private IPath workingDirectoryPath;
	private boolean gnuMake = false;
	private boolean incrementalDependencyRefresh = false;
	private boolean singleMode = false;
	private boolean dynamicLinking = false;
	private String etsName = null;
	private boolean useAbsolutePathNames = false;
	private boolean useRuntime2 = false;
	private String codeSplittingMode = null;
	private boolean library = false;
	private boolean usingSymbolicLinks = true;
	private boolean allProjectsUseSymbolicLinks = true;
	// not yet used
	@SuppressWarnings("unused")
	private ProductIdentity compilerProductNumber;

	/**
	 * right now not set
	 */
	private static final boolean useCrossCompilation = false;

	private List<IProject> reachableProjects;
	
	private boolean centralStorage;
	
	public InternalMakefileGenerator(final IProject project) {
		this.project = project;
	}
	
	/**
	 * Converts all directories used to generate the Makefile to relative
	 * pathnames based on the working directory.
	 */
	public void convertDirsToRelative() {
		for (final ModuleStruct module : ttcn3Modules) {
			if (module.getDirectory() != null) {
				module.setDirectory(PathUtil.getRelativePath(workingDirectory, module.getDirectory()));
			}
			if (module.getOriginalLocation() != null) {
				module.setOriginalLocation(PathUtil.getRelativePath(workingDirectory, module.getOriginalLocation()));
			}
		}
		for (final ModuleStruct module : ttcnppModules) {
			if (module.getDirectory() != null) {
				module.setDirectory(PathUtil.getRelativePath(workingDirectory, module.getDirectory()));
			}
			if (module.getOriginalLocation() != null) {
				module.setOriginalLocation(PathUtil.getRelativePath(workingDirectory, module.getOriginalLocation()));
			}
		}
		for (final TTCN3IncludeFileStruct includeFile : ttcn3IncludeFiles) {
			if (includeFile.getDirectory() != null) {
				includeFile.setDirectory(PathUtil.getRelativePath(workingDirectory, includeFile.getDirectory()));
			}
			if (includeFile.getWorkspaceDirectory() != null) {
				includeFile.setWorkspaceDirectory(PathUtil.getRelativePath(workingDirectory, includeFile.getWorkspaceDirectory()));
			}
			if (includeFile.getOriginalLocation() != null) {
				includeFile.setOriginalLocation(PathUtil.getRelativePath(workingDirectory, includeFile.getOriginalLocation()));
			}
			if (includeFile.getWorkspaceLocation() != null) {
				includeFile.setWorkspaceLocation(PathUtil.getRelativePath(workingDirectory, includeFile.getWorkspaceLocation()));
			}
		}
		for (final ModuleStruct module : asn1modules) {
			if (module.getDirectory() != null) {
				module.setDirectory(PathUtil.getRelativePath(workingDirectory, module.getDirectory()));
			}
			if (module.getOriginalLocation() != null) {
				module.setOriginalLocation(PathUtil.getRelativePath(workingDirectory, module.getOriginalLocation()));
			}
		}
		for (final UserStruct user : userFiles) {
			if (user.getDirectory() != null) {
				user.setDirectory(PathUtil.getRelativePath(workingDirectory, user.getDirectory()));
			}
			if (user.getOriginalHeaderLocation() != null) {
				user.setOriginalHeaderLocation(PathUtil.getRelativePath(workingDirectory, user.getOriginalHeaderLocation()));
			}
			if (user.getOriginalSourceLocation() != null) {
				user.setOriginalSourceLocation(PathUtil.getRelativePath(workingDirectory, user.getOriginalSourceLocation()));
			}
		}
		for (final OtherFileStruct other : otherFiles) {
			if (other.getDirectory() != null) {
				other.setDirectory(PathUtil.getRelativePath(workingDirectory, other.getDirectory()));
			}
			if (other.getOriginalLocation() != null) {
				other.setOriginalLocation(PathUtil.getRelativePath(workingDirectory, other.getOriginalLocation()));
			}
		}

		for (final BaseDirectoryStruct dir : baseDirectories) {
			if (dir.getDirectoryName() != null) {
				dir.setDirectoryName(PathUtil.getRelativePath(workingDirectory, dir.getDirectoryName()));
			}
		}

		for (final BaseDirectoryStruct dir : additionallyIncludedFolders) {
			if (dir.getDirectoryName() != null) {
				dir.setDirectoryName(PathUtil.getRelativePath(workingDirectory, dir.getDirectoryName()));
			}
		}

		if (etsName != null) {
			final Path path = new Path(etsName);
			if (path.segmentCount() > 1) {
				etsName = PathUtil.getRelativePath(workingDirectory, etsName);
			}
		}
	}

	/**
	 * Converts all directory paths used to generate the Makefile to their
	 * platform dependent form.
	 * <p/>
	 * In case of cygwin we extract path in windows format, but need to
	 * generate them in cygwin format in the Makefile.
	 */
	private void convertDirectories() {
		final boolean reportDebugInformation = Platform.getPreferencesService().getBoolean(ProductConstants.PRODUCT_ID_DESIGNER,
				PreferenceConstants.DISPLAYDEBUGINFORMATION, false, null);

		if (!Platform.OS_WIN32.equals(Platform.getOS())) {
			return;
		}

		final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
			@Override
			public Thread newThread(final Runnable r) {
				final Thread t = new Thread(r);
				t.setPriority(LoadBalancingUtilities.getThreadPriority());
				return t;
			}
		});
		final CountDownLatch latch = new CountDownLatch(ttcn3Modules.size() + ttcnppModules.size() + ttcn3IncludeFiles.size() + asn1modules.size() + userFiles.size() + otherFiles.size()
				+ baseDirectories.size() + additionallyIncludedFolders.size() + etsName != null ? 1 : 0);

		for (final ModuleStruct module : ttcn3Modules) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					if (module.getDirectory() != null) {
						final StringBuilder output = new StringBuilder();
						module.setDirectory(PathConverter.convert(module.getDirectory(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					if (module.getOriginalLocation() != null) {
						final StringBuilder output = new StringBuilder();
						module.setOriginalLocation(PathConverter.convert(module.getOriginalLocation(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					latch.countDown();
				}
			});
		}
		for (final ModuleStruct module : ttcnppModules) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					if (module.getDirectory() != null) {
						final StringBuilder output = new StringBuilder();
						module.setDirectory(PathConverter.convert(module.getDirectory(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					if (module.getOriginalLocation() != null) {
						final StringBuilder output = new StringBuilder();
						module.setOriginalLocation(PathConverter.convert(module.getOriginalLocation(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					latch.countDown();
				}
			});
		}

		for (final TTCN3IncludeFileStruct includeFile : ttcn3IncludeFiles) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					if (includeFile.getDirectory() != null) {
						final StringBuilder output = new StringBuilder();
						includeFile.setDirectory(PathConverter.convert(includeFile.getDirectory(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					if (includeFile.getOriginalLocation() != null) {
						final StringBuilder output = new StringBuilder();
						includeFile.setOriginalLocation(PathConverter.convert(includeFile.getOriginalLocation(), reportDebugInformation,
								output));
						TITANDebugConsole.println(output);
					}
					latch.countDown();
				}
			});
		}

		for (final ModuleStruct module : asn1modules) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					if (module.getDirectory() != null) {
						final StringBuilder output = new StringBuilder();
						module.setDirectory(PathConverter.convert(module.getDirectory(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					if (module.getOriginalLocation() != null) {
						final StringBuilder output = new StringBuilder();
						module.setOriginalLocation(PathConverter.convert(module.getOriginalLocation(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					latch.countDown();
				}
			});
		}
		for (final UserStruct user : userFiles) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					if (user.getDirectory() != null) {
						final StringBuilder output = new StringBuilder();
						user.setDirectory(PathConverter.convert(user.getDirectory(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					if (user.getOriginalHeaderLocation() != null) {
						final StringBuilder output = new StringBuilder();
						user.setOriginalHeaderLocation(PathConverter.convert(user.getOriginalHeaderLocation(), reportDebugInformation,
								output));
						TITANDebugConsole.println(output);
					}
					if (user.getOriginalSourceLocation() != null) {
						final StringBuilder output = new StringBuilder();
						user.setOriginalSourceLocation(PathConverter.convert(user.getOriginalSourceLocation(), reportDebugInformation,
								output));
						TITANDebugConsole.println(output);
					}
					latch.countDown();
				}
			});
		}
		for (final OtherFileStruct other : otherFiles) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					if (other.getDirectory() != null) {
						final StringBuilder output = new StringBuilder();
						other.setDirectory(PathConverter.convert(other.getDirectory(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					if (other.getOriginalLocation() != null) {
						final StringBuilder output = new StringBuilder();
						other.setOriginalLocation(PathConverter.convert(other.getOriginalLocation(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					latch.countDown();
				}
			});
		}

		for (final BaseDirectoryStruct dir : baseDirectories) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					if (dir.getDirectoryName() != null) {
						final StringBuilder output = new StringBuilder();
						dir.setDirectoryName(PathConverter.convert(dir.getDirectoryName(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					latch.countDown();
				}
			});
		}

		for (final BaseDirectoryStruct dir : additionallyIncludedFolders) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					if (dir.getDirectoryName() != null) {
						final StringBuilder output = new StringBuilder();
						dir.setDirectoryName(PathConverter.convert(dir.getDirectoryName(), reportDebugInformation, output));
						TITANDebugConsole.println(output);
					}
					latch.countDown();
				}
			});
		}

		if (etsName != null) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					final Path path = new Path(etsName);
					if (path.segmentCount() > 1) {
						final StringBuilder output = new StringBuilder();
						etsName = PathConverter.convert(etsName, reportDebugInformation, output);
						TITANDebugConsole.println(output);
					}
					latch.countDown();
				}
			});
		}

		try {
			latch.await();
		} catch (InterruptedException e) {
			ErrorReporter.logExceptionStackTrace(e);
		}
		executor.shutdown();
		try {
			executor.awaitTermination(30, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			ErrorReporter.logExceptionStackTrace(e);
		}
		executor.shutdownNow();
	}

	/**
	 * Checks if the provided path contains special characters or not. If
	 * there are any special characters the Makefile can not be generated
	 * safely.
	 *
	 * @param path the path to check for special characters
	 * @return true if the provided path contains special characters
	 */
	private boolean hasSpecialCharacters(final String path) {
		if (path == null) {
			return false;
		}

		char actual;
		for (int i = 0; i < path.length(); i++) {
			actual = path.charAt(i);
			switch (actual) {
				case ' ':
				case '*':
				case '?':
				case '[':
				case ']':
				case '<':
				case '=':
				case '>':
				case '|':
				case '&':
				case '$':
				case '%':
				case '{':
				case '}':
				case ';':
				case ':':
				case '(':
				case ')':
				case '#':
				case '!':
				case '\'':
				case '"':
				case '`':
				case '\\':
					return true;
				default:
					break;
			}
		}

		return false;
	}

	/**
	 * Checks all of the paths used to generate the Makefile whether they
	 * contain special characters or not. If there are any special
	 * characters the Makefile can not be generated safely.
	 * <p/>
	 * In case of an error it will be reported to the error log.
	 *
	 * @return true if any of the paths contains a special character
	 */
	private boolean checkSpecialCharacters() {
		boolean isErroneous = false;
		final StringBuilder errorBuilder = new StringBuilder();

		for (final ModuleStruct module : ttcn3Modules) {
			if (hasSpecialCharacters(module.getDirectory()) || hasSpecialCharacters(module.getFileName())) {
				final String path = module.getDirectory() == null ? "" : (module.getDirectory() + File.separatorChar);
				errorBuilder.append("The path of the TTCN-3 file `" + path + module.getFileName() + "' contains special characters,"
						+ " that cannot be handled properly by the `make' utility and/or the shell.\n");
				isErroneous = true;
			}
		}

		for (final ModuleStruct module : ttcnppModules) {
			if (hasSpecialCharacters(module.getDirectory()) || hasSpecialCharacters(module.getFileName())) {
				final String path = module.getDirectory() == null ? "" : (module.getDirectory() + File.separatorChar);
				errorBuilder.append("The path of the TTCN-3 file to be preprocessed `" + path + module.getFileName()
						+ "' contains special characters,"
						+ " that cannot be handled properly by the `make' utility and/or the shell.\n");
				isErroneous = true;
			}
		}

		for (final ModuleStruct module : asn1modules) {
			if (hasSpecialCharacters(module.getDirectory()) || hasSpecialCharacters(module.getFileName())) {
				final String path = module.getDirectory() == null ? "" : (module.getDirectory() + File.separatorChar);
				errorBuilder.append("The path of the ASN.1 file `" + path + module.getFileName() + "' contains special characters,"
						+ " that cannot be handled properly by the `make' utility and/or the shell.\n");
				isErroneous = true;
			}
		}

		for (final UserStruct user : userFiles) {
			if (hasSpecialCharacters(user.getDirectory()) || hasSpecialCharacters(user.getFileName())) {
				final String path = user.getDirectory() == null ? "" : (user.getDirectory() + File.separatorChar);
				if (user.isHasHHSuffix()) {
					errorBuilder.append("The path of the C/C++ header file `" + path + user.getFileName()
							+ "' contains special characters,"
							+ " that cannot be handled properly by the `make' utility and/or the shell.\n");
				} else {
					errorBuilder.append("The path of the C/C++ source file `" + path + user.getFileName()
							+ "' contains special characters,"
							+ " that cannot be handled properly by the `make' utility and/or the shell.\n");
				}
				isErroneous = true;
			}
		}

		for (final OtherFileStruct other : otherFiles) {
			if (hasSpecialCharacters(other.getDirectory()) || hasSpecialCharacters(other.getFileName())) {
				final String path = other.getDirectory() == null ? "" : (other.getDirectory() + File.separatorChar);
				errorBuilder.append("The path of the file `" + path + other.getFileName() + "' contains special characters,"
						+ " that cannot be handled properly by the `make' utility and/or the shell.\n");
				isErroneous = true;
			}
		}

		if (isErroneous) {
			ErrorReporter.logError(errorBuilder.toString());
			ErrorReporter.parallelErrorDisplayInMessageDialog(
					"Special character error", 
					errorBuilder.toString() + "Makefile was not generated!\n");
		}

		return isErroneous;
	}

	/**
	 * Checks the naming convention for all of the modules and files that
	 * are used to generate the Makefile. As if the naming conventions are
	 * kept, it is possible to generate a smaller Makefile.
	 */
	private void checkNamingConvention() {
		ttcn3ModulesRegular = true;
		baseTTCN3ModulesRegular = true;
		asn1ModulesRegular = true;
		baseASN1ModulesRegular = true;
		userHeadersRegular = true;
		userSourcesRegular = true;
		baseUserHeadersRegular = true;
		baseUserSourcesRegular = true;

		ModuleStruct module;
		for (int i = 0; i < ttcn3Modules.size() && (ttcn3ModulesRegular || baseTTCN3ModulesRegular); i++) {
			module = ttcn3Modules.get(i);
			if (module.getDirectory() == null) {
				if (!module.isRegular()) {
					ttcn3ModulesRegular = false;
				}
			} else {
				if (!module.isRegular()) {
					baseTTCN3ModulesRegular = false;
				}
			}
		}

		for (int i = 0; i < ttcnppModules.size() && (ttcn3ModulesRegular || baseTTCN3ModulesRegular); i++) {
			module = ttcnppModules.get(i);
			if (module.getDirectory() == null) {
				if (!module.isRegular()) {
					ttcn3ModulesRegular = false;
				}
			} else {
				if (!module.isRegular()) {
					baseTTCN3ModulesRegular = false;
				}
			}
		}

		for (int i = 0; i < asn1modules.size() && (asn1ModulesRegular || baseASN1ModulesRegular); i++) {
			module = asn1modules.get(i);
			if (module.getDirectory() == null) {
				if (!module.isRegular()) {
					asn1ModulesRegular = false;
				}
			} else {
				if (!module.isRegular()) {
					baseASN1ModulesRegular = false;
				}
			}
		}
		UserStruct user;
		boolean hasRegular = true;
		for (int i = 0; i < userFiles.size() && hasRegular; i++) {
			user = userFiles.get(i);
			if (user.getDirectory() != null) {
				if (!user.isHasCCSuffix()) {
					baseUserSourcesRegular = false;
				}
				if (!user.isHasCCSuffix() || !user.isHasHHSuffix()) {
					baseUserHeadersRegular = false;
				}
			} else {
				if (!user.isHasCCSuffix()) {
					userSourcesRegular = false;
				}
				if (!user.isHasCCSuffix() || !user.isHasHHSuffix()) {
					userHeadersRegular = false;
				}
			}

			hasRegular = userHeadersRegular || userSourcesRegular || baseUserHeadersRegular || baseUserSourcesRegular;
		}
	}

	private String getSplittingMode() {
		if (GeneralConstants.TYPE.equals(codeSplittingMode) || GeneralConstants.NONE.equals(codeSplittingMode)) {
			return codeSplittingMode;
		} else {
			try {
				Integer.parseInt(codeSplittingMode);
				return GeneralConstants.NUMBER;
			} catch (NumberFormatException e) {
				e.printStackTrace();
			}
			return GeneralConstants.NONE;
		}
	}

	private StringBuilder getSplittedFilenames(final ModuleStruct module, final boolean printDir) {
		final StringBuilder contents = new StringBuilder();
		if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
			contents.append(' ').append(module.generatedName(printDir, "cc", "_seq"));
			contents.append(' ').append(module.generatedName(printDir, "cc", "_seqof"));
			contents.append(' ').append(module.generatedName(printDir, "cc", "_set"));
			contents.append(' ').append(module.generatedName(printDir, "cc", "_setof"));
			contents.append(' ').append(module.generatedName(printDir, "cc", "_union"));
		} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
		} else {
			final int n_slices = Integer.parseInt(codeSplittingMode);
			for (int i = 1; i < n_slices; i++) {
				contents.append(' ').append(module.generatedName(printDir, "cc", "_part_"+i));
			}
		}
		return contents;
	}

	/**
	 * Sort the list alphabetically according to the file names. This should
	 * make the generated Makefile more predictable or comparable.
	 */
	private void sortFiles() {
		Collections.sort(baseDirectories);
		Collections.sort(additionallyIncludedFolders);
		Collections.sort(ttcn3Modules);
		Collections.sort(ttcn3IncludeFiles);
		Collections.sort(ttcnppModules);
		Collections.sort(asn1modules);
		Collections.sort(userFiles);
		Collections.sort(otherFiles);
	}

	/**
	 * Generates the Makefile for the project provided.
	 *
	 * @param project the project for which the Makefile should be
	 *                generated.
	 */
	public void generateMakefile() {

		if(Cygwin.isMissingInOSWin32()) {
			ErrorReporter.logError(MISSING_CYGWIN);
			return;
		}

		final boolean reportDebugInformation = Platform.getPreferencesService().getBoolean(ProductConstants.PRODUCT_ID_DESIGNER,
				PreferenceConstants.DISPLAYDEBUGINFORMATION, false, null);

		this.projectLocation = project.getLocation().toOSString();
		final ProjectSourceParser projectSourceParser = GlobalParser.getProjectSourceParser(project);

		// If not yet analyzed do it now (this will also analyze all
		// referenced projects.)
		projectSourceParser.makefileCreatingAnalyzeAll();

		try {
			setParameters();
		} catch (CoreException e) {
			ErrorReporter.logExceptionStackTrace(e);
			return;
		}


		gatherInformation();
		
		if (!useAbsolutePathNames) {
			convertDirsToRelative();
		}

		convertDirectories();

		if (checkSpecialCharacters()) {
			// An error was discovered and reported, Makefile
			// generation must stop
			return;
		}

		checkNamingConvention();

		sortFiles();
		final StringBuilder contents = new StringBuilder();
		contents.append("# This Makefile was generated by the TITAN Designer eclipse plug-in\n");
		contents.append("# of the TTCN-3 Test executor version ").append(GeneralConstants.VERSION_STRING).append('\n');
		contents.append("# for  (").append(System.getProperty("user.name")).append('@');
		try {
			contents.append(InetAddress.getLocalHost().getHostName());
		} catch (UnknownHostException e) {
			contents.append("unknown");
		}
		final String pattern = "MMMM dd yyyy";
		final SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
		final String date = simpleDateFormat.format(new Date());

		contents.append(") on ").append(date).append("\n\n");
		contents.append("# ").append(CommentUtils.COPYRIGHT_TEXT).append('\n');
		contents.append('\n');
		contents.append("# The following make commands are available:\n");
		contents.append("# - make, make all      ");
		if (library) {
			contents.append("Builds the library archive: '" + getLibraryName() + "'.\n");
		} else {
			contents.append("Builds the executable test suite.\n");
		}
		contents.append("# - make archive        Archives all source files.\n");
		contents.append("# - make check          Checks the semantics of TTCN-3 and ASN.1 modules.\n");
		contents.append("# - make port           Generates port skeletons.\n");
		contents.append("# - make clean          Removes all generated files.\n");
		contents.append("# - make compile        Translates TTCN-3 and ASN.1 modules to C++.\n");
		contents.append("# - make dep            Creates/updates dependency list.\n");
		contents.append("# - make executable     Builds the executable test suite.\n");
		contents.append("# - make library        Builds the library archive: '" + getLibraryName() + "'.\n");
		contents.append("# - make objects        Builds the object files without linking the executable.\n");
		if (dynamicLinking) {
			contents.append("# - make shared_objects Builds the shared object files without linking the executable.\n");
		}
		if (preprocess) {
			contents.append("# - make preprocess     Preprocess TTCN-3 files.\n");
		}

		if (centralStorage) {
			contents.append("# WARNING! This Makefile uses pre-compiled files from the following directories:\n");
			for (final BaseDirectoryStruct dir : baseDirectories) {
				contents.append("# ").append(dir.name()).append('\n');
			}
			contents.append("# The executable tests will be consistent only if all directories use\n");
			contents.append("# the same platform and the same version of TTCN-3 Test Executor and\n");
			contents.append("# C++ compiler with the same command line switches.\n\n");
		}
		if (gnuMake) {
			contents.append("# WARNING! This Makefile can be used with GNU make only.\n");
			contents.append("# Other versions of make may report syntax errors in it.\n\n");
			contents.append("#\n");
			contents.append("# Do NOT touch this line...\n");
			contents.append("#\n");
			contents.append(".PHONY: all");
			if (dynamicLinking) {
				contents.append(" shared_objects");
			}
			contents.append(" executable library objects check port clean dep archive");
			if (preprocess) {
				contents.append(" preprocess");
			}
			contents.append("\n\n");
			if (incrementalDependencyRefresh) {
				contents.append(".SUFFIXES: .d\n\n");
			}
		}
		contents.append("#\n");
		contents.append("# Set these variables...\n");
		contents.append("#\n");
		contents.append('\n');
		contents.append("# The path of your TTCN-3 Test Executor installation:\n");
		contents.append("# Uncomment this line to override the environment variable.\n");
		contents.append("# TTCN3_DIR =\n");

		final String platform = Platform.getOS();
		String platformString = null;
		if (Platform.OS_LINUX.equals(platform)) {
			platformString = "LINUX";
		} else if (Platform.OS_WIN32.equals(platform)) {
			platformString = "WIN32";
		} else if (Platform.OS_SOLARIS.equals(platform)) {
			platformString = "SOLARIS";
			// FIXME implement precise platform string
		}

		if (useCrossCompilation) {
			contents.append("# TTCN3_TARGET_DIR = $(TTCN3_DIR)\n\n");
			contents.append("# Your platform: (SOLARIS, SOLARIS8, LINUX, FREEBSD or WIN32)\n");
			contents.append("PLATFORM = ").append(platformString).append("\n\n");
			contents.append("# The path of your toolchain including the target triplet:\n");
			contents.append("CROSSTOOL_DIR =\n\n");
			contents.append("# Your C++ compiler:\n");
			contents.append("# (if you change the platform, you may need to change the compiler)\n");
			contents.append("CXX = g++\n\n");
		} else {
			contents.append('\n');
			contents.append("# Your platform: (SOLARIS, SOLARIS8, LINUX, FREEBSD or WIN32)\n");
			contents.append("PLATFORM = ").append(platformString).append("\n\n");
			contents.append("# Your C++ compiler:\n");
			contents.append("# (if you change the platform, you may need to change the compiler)\n");
			String compilerName = CCompilerOptionsData.getCompilerName(project);
			if (compilerName == null || compilerName.length() == 0) {
				ErrorReporter.parallelErrorDisplayInMessageDialog(
					"Errors detected during Makefile generation",
					"The C/C++ compiler tool is not set for the project " + project.getName() 
						+ " using g++ as default.");
				compilerName = "g++";
			}
			contents.append("CXX = ").append(compilerName).append("\n\n");
		}

		if (preprocess) {
			contents.append("# C preprocessor used for TTCN-3 files:\n");
			String preprocessorName = TTCN3PreprocessorOptionsData.getPreprocessorName(project);
			if (preprocessorName == null || preprocessorName.length() == 0) {
				ErrorReporter.parallelErrorDisplayInMessageDialog(
					"Errors detected during Makefile generation",
					"The preprocessor tool is not set for the project " + project.getName()
						+ " using cpp as default.");
				preprocessorName = "cpp";
			}
			contents.append("CPP = ").append(preprocessorName).append("\n\n");
		}
		contents.append("# Flags for the C++ preprocessor (and makedepend as well):\n");
		contents.append("CPPFLAGS = -D$(PLATFORM) -I. -I$(TTCN3_DIR)/include");

		if (useRuntime2) {
			contents.append(" -DTITAN_RUNTIME_2");
		}

		for (final IProject reachableProject : reachableProjects) {
			final String[] optionList = PreprocessorIncludedOptionsData.getPreprocessorIncludes(reachableProject);
			if (optionList.length > 0) {
				final IPath location = reachableProject.getLocation();
				if (location == null) {
					ErrorReporter.logError("The project `"
							+ reachableProject.getName()
							+ "' is not located in the local file system. The extra include directories set for it will not be generated into the Makefile");
				} else {
					final String tempProjectLocation = location.toOSString();
					for (String temp : optionList) {
						final StringBuilder output = new StringBuilder();
						temp = TITANPathUtilities.resolvePathURIForMakefile(temp, 
								tempProjectLocation, reportDebugInformation, output);
						contents.append(" -I").append(temp);
						TITANDebugConsole.println(output);
						
					}
				}
			}
		}
		for (final IProject reachableProject : reachableProjects) {
			final String[] optionList = PreprocessorSymbolsOptionsData.getPreprocessorDefines(reachableProject);
			for (final String option : optionList) {
				contents.append(" -D").append(option);
			}
		}
		for (final IProject reachableProject : reachableProjects) {
			final String[] optionList = PreprocessorSymbolsOptionsData.getPreprocessorUndefines(reachableProject);
			for (final String option : optionList) {
				contents.append(" -U").append(option);
			}
		}

		for (final BaseDirectoryStruct dir : baseDirectories) {
			contents.append(" -I").append(dir.name());
		}

		if (!usingSymbolicLinks) {
			for (final BaseDirectoryStruct dir : additionallyIncludedFolders) {
				contents.append(" -I").append(dir.name());
			}
		}
		contents.append("\n\n");

		if (incrementalDependencyRefresh) {
			contents.append("# Flags for dependency generation\n");
			// -xM1 for SunPro
			contents.append("CXXDEPFLAGS = -MM\n\n");
		}

		if (preprocess) {
			contents.append("# Flags for preprocessing TTCN-3 files:\n");
			contents.append("CPPFLAGS_TTCN3 = -I.");
			for (final BaseDirectoryStruct dir : baseDirectories) {
				contents.append(" -I").append(dir.name());
			}
			if (!usingSymbolicLinks) {
				for (final BaseDirectoryStruct dir : additionallyIncludedFolders) {
					contents.append(" -I").append(dir.name());
				}
			}
			for (final IProject reachableProject : reachableProjects) {
				final String[] optionList = PreprocessorIncludedOptionsData.getTTCN3PreprocessorIncludes(reachableProject);
				if (optionList.length > 0) {
					final IPath location = reachableProject.getLocation();
					if (location == null) {
						ErrorReporter.logError("The project `"
								+ reachableProject.getName()
								+ "' is not located in the local file system. The extra preprocessor include directories set for it will not be generated into the Makefile");
					} else {
						final String tempProjectLocation = location.toOSString();
						for (String option : optionList) {
							final StringBuilder output = new StringBuilder();
							option = TITANPathUtilities.resolvePathURIForMakefile(option, 
									tempProjectLocation, reportDebugInformation, output);
							contents.append(" -I").append(option);
							TITANDebugConsole.println(output);
						}
					}
				}
			}
			for (final IProject reachableProject : reachableProjects) {
				final String[] optionList = PreprocessorSymbolsOptionsData.getTTCN3PreprocessorDefines(reachableProject);
				for (final String option : optionList) {
					contents.append(" -D").append(option);
				}
			}
			for (final IProject reachableProject : reachableProjects) {
				final String[] optionList = PreprocessorSymbolsOptionsData.getTTCN3PreprocessorUndefines(reachableProject);
				for (final String option : optionList) {
					contents.append(" -U").append(option);
				}
			}
			contents.append("\n\n");
		}

		contents.append("# Flags for the C++ compiler:\n");
		contents.append("CXXFLAGS = -Wall").append(COptimalizationOptionsData.getCxxOptimizationFlags(project));
		if (dynamicLinking) {
			// The extra space is not necessary here, hence the
			// previous
			// append() will add one to the end, but be consistent.
			contents.append(" -fPIC");
		}
		contents.append("\n\n");
		contents.append("# Flags for the linker:\n");
		contents.append("LDFLAGS = ");
		if (useCrossCompilation) {
			contents.append("-L$(CROSSTOOL_DIR)/lib ");
		}
		contents.append( LinkerFlagsOptionsData.getLinkerFlags(project));
		if (dynamicLinking) {
			contents.append(" -fPIC");
		}
		contents.append("\n\n");
		contents.append("ifeq ($(PLATFORM), WIN32)\n");
		contents.append("# Silence linker warnings.\n");
		contents.append("LDFLAGS += -Wl,--enable-auto-import,--enable-runtime-pseudo-reloc\n");
		contents.append("endif\n\n");

		contents.append("# Utility to create library files\n");
		contents.append("AR = ar\n");
		contents.append("ARFLAGS = \n\n");

		contents.append("# Flags for the TTCN-3 and ASN.1 compiler:\n");
		contents.append("# ").append(TITANFlagsOptionsData.getTITANFlagComments(project, useRuntime2)).append('\n');
		contents.append("COMPILER_FLAGS = ").append(TITANFlagsOptionsData.getTITANFlags(project, useRuntime2)).append("\n\n");
		contents.append("# Execution mode: ");
		contents.append(useRuntime2 ? "function" : "load").append(" test runtime in ");
		contents.append(singleMode ? "single" : "parallel").append(" mode");
		contents.append(dynamicLinking ? " with dynamic linking" : "").append('\n');
		contents.append("TTCN3_LIB = ttcn3");
		if (useRuntime2) {
			contents.append("-rt2");
		}
		if (!singleMode) {
			contents.append("-parallel");
		}
		if (dynamicLinking) {
			contents.append("-dynamic");
		}
		contents.append("\n\n");
		contents.append("# The path of your OpenSSL installation:\n");
		contents.append("# If you do not have your own one, leave it unchanged.\n");

		final String ttcn3Lib = useCrossCompilation ? "TARGET_" : "";
		final boolean externalLibrariesDisabled = LinkerLibrariesOptionsData.getExternalFoldersDisabled(project);
		if (externalLibrariesDisabled) {
			contents.append("# OPENSSL_DIR = $(TTCN3_").append(ttcn3Lib).append("DIR)\n\n");
		} else {
			contents.append("OPENSSL_DIR = $(TTCN3_").append(ttcn3Lib).append("DIR)\n\n");
		}

		contents.append("# The path of your libxml2 installation:\n");
		contents.append("# If you do not have your own one, leave it unchanged.\n");
		if (externalLibrariesDisabled) {
			contents.append("# XMLDIR = $(TTCN3_").append(ttcn3Lib).append("DIR)\n\n");
		} else {
			contents.append("XMLDIR = $(TTCN3_").append(ttcn3Lib).append("DIR)\n\n");
		}
		contents.append("# Directory to store the archived source files:\n");

		if (!gnuMake) {
			contents.append("# Note: you can set any directory except ./archive\n");
		}

		contents.append("ARCHIVE_DIR = backup\n\n");
		contents.append("#\n");
		contents.append("# You may change these variables. Add your files if necessary...\n");
		contents.append("#\n\n");
		contents.append("# TTCN-3 modules of this project:\n");
		contents.append("TTCN3_MODULES =");
		for (final ModuleStruct module : ttcn3Modules) {
			if (module.getDirectory() == null || !centralStorage) {
				if (usingSymbolicLinks) {
					contents.append(' ').append(module.fileName());
				} else {
					contents.append(' ').append(module.getOriginalLocation());
				}
			}
		}

		if (preprocess) {
			contents.append("\n\n");
			contents.append("# TTCN-3 modules to preprocess:\n");
			contents.append("TTCN3_PP_MODULES =");
			for (final ModuleStruct module : ttcnppModules) {
				if (module.getDirectory() == null || !centralStorage) {
					if (usingSymbolicLinks) {
						contents.append(' ').append(module.fileName());
					} else {
						contents.append(' ').append(module.getOriginalLocation());
					}
				}
			}
		}

		if (centralStorage) {
			contents.append("\n\n");
			contents.append("# TTCN-3 modules used from central project(s):\n");
			contents.append("BASE_TTCN3_MODULES =");
			for (final ModuleStruct module : ttcn3Modules) {
				if (module.getDirectory() != null) {
					if (allProjectsUseSymbolicLinks) {
						contents.append(' ').append(module.fileName());
					} else {
						contents.append(' ').append(module.getOriginalLocation());
					}
				}
			}
			if (preprocess) {
				contents.append("\n\n");
				contents.append("# TTCN-3 modules to preprocess used from central project(s):\n");
				contents.append("BASE_TTCN3_PP_MODULES =");
				for (final ModuleStruct module : ttcnppModules) {
					if (module.getDirectory() != null) {
						if (allProjectsUseSymbolicLinks) {
							contents.append(' ').append(module.fileName());
						} else {
							contents.append(' ').append(module.getOriginalLocation());
						}
					}
				}
			}
		}

		if (preprocess) {
			contents.append("\n\n");
			contents.append("# Files to include in TTCN-3 preprocessed modules:\n");
			contents.append("TTCN3_INCLUDES =");
			for (final TTCN3IncludeFileStruct temp : ttcn3IncludeFiles) {
				if (usingSymbolicLinks) { 
					contents.append(' ').append(temp.getWorkspaceLocation());
				} else {
					contents.append(' ').append(temp.getOriginalLocation());
				}
			}
		}

		contents.append("\n\n");
		contents.append("# ASN.1 modules of this project:\n");
		contents.append("ASN1_MODULES =");
		for (final ModuleStruct module : asn1modules) {
			if (module.getDirectory() == null || !centralStorage) {
				if (usingSymbolicLinks) {
					contents.append(' ').append(module.fileName());
				} else {
					contents.append(' ').append(module.getOriginalLocation());
				}
			}
		}

		if (centralStorage) {
			contents.append("\n\n");
			contents.append("# ASN.1 modules used from central project(s):\n");
			contents.append("BASE_ASN1_MODULES =");
			for (final ModuleStruct module : asn1modules) {
				if (module.getDirectory() != null) {
					if (allProjectsUseSymbolicLinks) {
						contents.append(' ').append(module.fileName());
					} else {
						contents.append(' ').append(module.getOriginalLocation());
					}
				}
			}
		}

		if (preprocess) {
			contents.append("\n\n");
			contents.append("# TTCN-3 source files generated by the C preprocessor:\n");
			contents.append("PREPROCESSED_TTCN3_MODULES =");
			for (final ModuleStruct module : ttcnppModules) {
				if (module.getDirectory() == null || !centralStorage) {
					contents.append(' ').append(module.preprocessedName(false));
				}
			}
			if (centralStorage) {
				contents.append("\n\n");
				contents.append("# TTCN-3 files generated by the CPP used from central project(s):\n");
				contents.append("BASE_PREPROCESSED_TTCN3_MODULES =");
				for (final ModuleStruct module : ttcnppModules) {
					if (module.getDirectory() != null) {
						contents.append(' ').append(module.preprocessedName(true));
					}
				}
			}
		}

		contents.append("\n\n");
		contents.append("# C++ source & header files generated from the TTCN-3 & ASN.1 ");
		contents.append("modules of\n");
		contents.append("# this project:\n");
		contents.append("GENERATED_SOURCES =");
		if (gnuMake && ttcn3ModulesRegular && allProjectsUseSymbolicLinks) {
			contents.append(" $(TTCN3_MODULES:.ttcn=.cc)");
			if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
				for (final ModuleStruct module : ttcn3Modules) {
					if (module.getDirectory() == null || !centralStorage) {
						contents.append(getSplittedFilenames(module, false));
					}
				}
			}
			if (preprocess) {
				contents.append(" $(TTCN3_PP_MODULES:.ttcnpp=.cc)");
				if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
					for (final ModuleStruct module : ttcnppModules) {
						if (module.getDirectory() == null || !centralStorage) {
							contents.append(getSplittedFilenames(module, false));
						}
					}
				}
			}
		} else {
			for (final ModuleStruct module : ttcn3Modules) {
				if (module.getDirectory() == null || !centralStorage) {
					contents.append(' ').append(module.generatedName(false, "cc"));
					if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
						contents.append(getSplittedFilenames(module, false));
					}
				}
			}
			if (preprocess) {
				for (final ModuleStruct module : ttcnppModules) {
					if (module.getDirectory() == null || !centralStorage) {
						contents.append(' ').append(module.generatedName(false, "cc"));
						if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
							contents.append(getSplittedFilenames(module, false));
						}
					}
				}
			}
		}

		if (gnuMake && asn1ModulesRegular && allProjectsUseSymbolicLinks) {
			contents.append(" $(ASN1_MODULES:.asn=.cc)");
			if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
				for (final ModuleStruct module : asn1modules) {
					if (module.getDirectory() == null || !centralStorage) {
						contents.append(getSplittedFilenames(module, false));
					}
				}
			}
		} else {
			for (final ModuleStruct module : asn1modules) {
				if (module.getDirectory() == null || !centralStorage) {
					contents.append(' ').append(module.generatedName(false, "cc"));
					if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
						contents.append(getSplittedFilenames(module, false));
					}
				}
			}
		}

		contents.append("\nGENERATED_HEADERS =");
		if (gnuMake) {
		    // If GNU make and split to slices code splitting set, then if we would
		    // use the .cc=.hh then the _part_i.hh header files would be printed into
		    // the makefile that would cause weird behavior.
			if (getSplittingMode().equals(GeneralConstants.NUMBER) || getSplittingMode().equals(GeneralConstants.TYPE)) {
				if (ttcn3ModulesRegular) {
					contents.append(" $(TTCN3_MODULES:.ttcn=.hh)");
					if (preprocess) {
						contents.append(" $(TTCN3_PP_MODULES:.ttcnpp=.hh)");
					}
				} else {
					for (final ModuleStruct module : ttcn3Modules) {
						if (module.getDirectory() == null || !centralStorage) {
							contents.append(' ').append(module.generatedName(false, "hh"));
						}
					}
					if (preprocess) {
						for (final ModuleStruct module : ttcnppModules) {
							if (module.getDirectory() == null || !centralStorage) {
								contents.append(' ').append(module.generatedName(false, "hh"));
							}
						}
					}
				}
				if (asn1ModulesRegular) {
					contents.append(" $(ASN1_MODULES:.asn=.hh)");
				} else {
					for (final ModuleStruct module : asn1modules) {
						if (module.getDirectory() == null || !centralStorage) {
							contents.append(' ').append(module.generatedName(false, "hh"));
						}
					}
				}
			} else {
				// Generate normally
				contents.append(" $(GENERATED_SOURCES:.cc=.hh)");
			}
		} else {
			for (final ModuleStruct module : ttcn3Modules) {
				if (module.getDirectory() == null || !centralStorage) {
					contents.append(' ').append(module.generatedName(false, "hh"));
				}
			}
			if (preprocess) {
				for (final ModuleStruct module : ttcnppModules) {
					if (module.getDirectory() == null || !centralStorage) {
						contents.append(' ').append(module.generatedName(false, "hh"));
					}
				}
			}
			for (final ModuleStruct module : asn1modules) {
				if (module.getDirectory() == null || !centralStorage) {
					contents.append(' ').append(module.generatedName(false, "hh"));
				}
			}
		}

		if (centralStorage) {
			contents.append("\n\n");
			contents.append("# C++ source & header files generated from the TTCN-3 & ASN.1 ");
			contents.append("modules of\n");
			contents.append("# central project(s):\n");
			contents.append("BASE_GENERATED_SOURCES =");
			if (gnuMake && baseTTCN3ModulesRegular && allProjectsUseSymbolicLinks) {
				contents.append(" $(BASE_TTCN3_MODULES:.ttcn=.cc)");
				if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
					for (final ModuleStruct module : ttcn3Modules) {
						if (module.getDirectory() != null) {
							contents.append(getSplittedFilenames(module, false));
						}
					}
				}
				if (preprocess) {
					contents.append(" $(BASE_TTCN3_PP_MODULES:.ttcnpp=.cc)");
					if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
						for (final ModuleStruct module : ttcnppModules) {
							if (module.getDirectory() != null) {
								contents.append(getSplittedFilenames(module, false));
							}
						}
					}
				}
			} else {
				for (final ModuleStruct module : ttcn3Modules) {
					if (module.getDirectory() != null) {
						contents.append(' ').append(module.generatedName(true, "cc"));
						if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
							contents.append(getSplittedFilenames(module, true));
						}
					}
				}
				if (preprocess) {
					for (final ModuleStruct module : ttcnppModules) {
						if (module.getDirectory() != null) {
							contents.append(' ').append(module.generatedName(true, "cc"));
							if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
								contents.append(getSplittedFilenames(module, true));
							}
						}
					}
				}
			}
			if (gnuMake && baseASN1ModulesRegular && allProjectsUseSymbolicLinks) {
				contents.append(" $(BASE_ASN1_MODULES:.asn=.cc)");
				if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
					for (final ModuleStruct module : asn1modules) {
						if (module.getDirectory() != null) {
							contents.append(getSplittedFilenames(module, false));
						}
					}
				}
			} else {
				for (final ModuleStruct module : asn1modules) {
					if (module.getDirectory() != null) {
						contents.append(' ').append(module.generatedName(true, "cc"));
						if (!GeneralConstants.NONE.equals(codeSplittingMode)) {
							contents.append(getSplittedFilenames(module, true));
						}
					}
				}
			}
			contents.append("\nBASE_GENERATED_HEADERS =");
			if (gnuMake) {
			    // If GNU make and split to slices code splitting set, then if we would
			    // use the .cc=.hh then the _part_i.hh header files would be printed into
			    // the makefile that would cause weird behavior.
				if (getSplittingMode().equals(GeneralConstants.NUMBER) || getSplittingMode().equals(GeneralConstants.TYPE)) {
					if (ttcn3ModulesRegular) {
						contents.append(" $(BASE_TTCN3_MODULES:.ttcn=.hh)");
						if (preprocess) {
							contents.append(" $(BASE_TTCN3_PP_MODULES:.ttcnpp=.hh)");
						}
					} else {
						for (final ModuleStruct module : ttcn3Modules) {
							if (module.getDirectory() != null) {
								contents.append(' ').append(module.generatedName(true, "hh"));
							}
						}
						if (preprocess) {
							for (final ModuleStruct module : ttcnppModules) {
								if (module.getDirectory() != null) {
									contents.append(' ').append(module.generatedName(true, "hh"));
								}
							}
						}
					}
					if (asn1ModulesRegular) {
						contents.append(" $(BASE_ASN1_MODULES:.asn=.hh)");
					} else {
						for (final ModuleStruct module : asn1modules) {
							if (module.getDirectory() != null) {
								contents.append(' ').append(module.generatedName(true, "hh"));
							}
						}
					}
				} else {
					// Generate normally
					contents.append(" $(BASE_GENERATED_SOURCES:.cc=.hh)");
				}
			} else {
				for (final ModuleStruct module : ttcn3Modules) {
					if (module.getDirectory() != null) {
						contents.append(' ').append(module.generatedName(true, "hh"));
					}
				}
				if (preprocess) {
					for (final ModuleStruct module : ttcnppModules) {
						if (module.getDirectory() != null) {
							contents.append(' ').append(module.generatedName(true, "hh"));
						}
					}
				}
				for (final ModuleStruct module : asn1modules) {
					if (module.getDirectory() != null) {
						contents.append(' ').append(module.generatedName(true, "hh"));
					}
				}
			}
		}

		contents.append("\n\n");
		contents.append("# C/C++ Source & header files of Test Ports, external functions ");
		contents.append("and\n");
		contents.append("# other modules:\n");
		contents.append("USER_SOURCES =");
		for (final UserStruct user : userFiles) {
			if (user.getDirectory() == null || !centralStorage) {
				if (!usingSymbolicLinks && user.getOriginalSourceLocation() != null) {
					contents.append(' ').append(user.getOriginalSourceLocation());
				} else {
					contents.append(' ').append(user.sourceName());
				}
			}
		}

		contents.append("\nUSER_HEADERS =");
		if (gnuMake && userHeadersRegular) {
			contents.append(" $(USER_SOURCES:.cc=.hh)");
		} else {
			for (final UserStruct user : userFiles) {
				if (user.getDirectory() == null || !centralStorage) {
					if (!usingSymbolicLinks && user.getOriginalHeaderLocation() != null) {
						contents.append(' ').append(user.getOriginalHeaderLocation());
					} else {
						contents.append(' ').append(user.headerName());
					}
				}
			}
		}
		if (centralStorage) {
			contents.append("\n\n");
			contents.append("# C/C++ Source & header files of Test Ports, external functions ");
			contents.append("and\n");
			contents.append("# other modules used from central project(s):\n");
			contents.append("BASE_USER_SOURCES =");
			for (final UserStruct user : userFiles) {
				if (user.getDirectory() != null) {
					contents.append(' ').append(user.sourceName());
				}
			}
			contents.append("\nBASE_USER_HEADERS =");
			if (gnuMake && baseUserHeadersRegular) {
				contents.append(" $(BASE_USER_SOURCES:.cc=.hh)");
			} else {
				for (final UserStruct user : userFiles) {
					if (user.getDirectory() != null) {
						contents.append(' ').append(user.headerName());
					}
				}
			}
		}

		contents.append("\n\n");
		contents.append("# Object files of this project that are needed for the executable ");
		contents.append("test suite:\n");
		contents.append("OBJECTS = $(GENERATED_OBJECTS) $(USER_OBJECTS)\n\n");
		contents.append("GENERATED_OBJECTS =");
		if (gnuMake) {
			contents.append(" $(GENERATED_SOURCES:.cc=.o)");
		} else {
			for (final ModuleStruct module : ttcn3Modules) {
				if (module.getDirectory() == null || !centralStorage) {
					contents.append(' ').append(module.generatedName(false, "o"));
					if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
						contents.append(' ').append(module.generatedName(false, "o", "_seq"));
						contents.append(' ').append(module.generatedName(false, "o", "_seqof"));
						contents.append(' ').append(module.generatedName(false, "o", "_set"));
						contents.append(' ').append(module.generatedName(false, "o", "_setof"));
						contents.append(' ').append(module.generatedName(false, "o", "_union"));
					} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
					} else {
						final int n_slices = Integer.parseInt(codeSplittingMode);
						for (int i = 1; i < n_slices; i++) {
							contents.append(' ').append(module.generatedName(false, "o", "_part_"+i));
						}
					}
				}
			}
			if (preprocess) {
				for (final ModuleStruct module : ttcnppModules) {
					if (module.getDirectory() == null || !centralStorage) {
						contents.append(' ').append(module.generatedName(false, "o"));
						if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
							contents.append(' ').append(module.generatedName(false, "o", "_seq"));
							contents.append(' ').append(module.generatedName(false, "o", "_seqof"));
							contents.append(' ').append(module.generatedName(false, "o", "_set"));
							contents.append(' ').append(module.generatedName(false, "o", "_setof"));
							contents.append(' ').append(module.generatedName(false, "o", "_union"));
						} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
						} else {
							final int n_slices = Integer.parseInt(codeSplittingMode);
							for (int i = 1; i < n_slices; i++) {
								contents.append(' ').append(module.generatedName(false, "o", "_part_"+i));
							}
						}
					}
				}
			}
			for (final ModuleStruct module : asn1modules) {
				if (module.getDirectory() == null || !centralStorage) {
					contents.append(' ').append(module.generatedName(false, "o"));
					if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
						contents.append(' ').append(module.generatedName(false, "o", "_seq"));
						contents.append(' ').append(module.generatedName(false, "o", "_seqof"));
						contents.append(' ').append(module.generatedName(false, "o", "_set"));
						contents.append(' ').append(module.generatedName(false, "o", "_setof"));
						contents.append(' ').append(module.generatedName(false, "o", "_union"));
					} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
					} else {
						final int n_slices = Integer.parseInt(codeSplittingMode);
						for (int i = 1; i < n_slices; i++) {
							contents.append(' ').append(module.generatedName(false, "o", "_part_"+i));
						}
					}
				}
			}
		}
		contents.append("\n\nUSER_OBJECTS =");
		if (gnuMake && userSourcesRegular && usingSymbolicLinks) {
			contents.append(" $(USER_SOURCES:.cc=.o)");
		} else {
			for (final UserStruct user : userFiles) {
				if (user.getDirectory() == null || !centralStorage) {
					final StringBuilder objectName = user.objectName();
					if (objectName != null) {
						contents.append(' ').append(objectName);
					}
				}
			}
		}

		if (dynamicLinking) {
			contents.append("\n\n");
			contents.append("# Shared object files of this project that are needed for the executable ");
			contents.append("test suite:\n");
			contents.append("SHARED_OBJECTS =");
			if (gnuMake) {
				contents.append(" $(GENERATED_SOURCES:.cc=.so)");
			} else {
				for (final ModuleStruct module : ttcn3Modules) {
					if (module.getDirectory() == null || !centralStorage) {
						contents.append(' ').append(module.generatedName(false, "so"));
						if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
							contents.append(' ').append(module.generatedName(false, "so", "_seq"));
							contents.append(' ').append(module.generatedName(false, "so", "_seqof"));
							contents.append(' ').append(module.generatedName(false, "so", "_set"));
							contents.append(' ').append(module.generatedName(false, "so", "_setof"));
							contents.append(' ').append(module.generatedName(false, "so", "_union"));
						} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
						} else {
							final int n_slices = Integer.parseInt(codeSplittingMode);
							for (int i = 1; i < n_slices; i++) {
								contents.append(' ').append(module.generatedName(false, "so", "_part_"+i));
							}
						}
					}
				}
				if (preprocess) {
					for (final ModuleStruct module : ttcnppModules) {
						if (module.getDirectory() == null || !centralStorage) {
							contents.append(' ').append(module.generatedName(false, "so"));
							if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
								contents.append(' ').append(module.generatedName(false, "so", "_seq"));
								contents.append(' ').append(module.generatedName(false, "so", "_seqof"));
								contents.append(' ').append(module.generatedName(false, "so", "_set"));
								contents.append(' ').append(module.generatedName(false, "so", "_setof"));
								contents.append(' ').append(module.generatedName(false, "so", "_union"));
							} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
							} else {
								final int n_slices = Integer.parseInt(codeSplittingMode);
								for (int i = 1; i < n_slices; i++) {
									contents.append(' ').append(module.generatedName(false, "so", "_part_"+i));
								}
							}
						}
					}
				}
				for (final ModuleStruct module : asn1modules) {
					if (module.getDirectory() == null || !centralStorage) {
						contents.append(' ').append(module.generatedName(false, "so"));
						if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
							contents.append(' ').append(module.generatedName(false, "so", "_seq"));
							contents.append(' ').append(module.generatedName(false, "so", "_seqof"));
							contents.append(' ').append(module.generatedName(false, "so", "_set"));
							contents.append(' ').append(module.generatedName(false, "so", "_setof"));
							contents.append(' ').append(module.generatedName(false, "so", "_union"));
						} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
						} else {
							final int n_slices = Integer.parseInt(codeSplittingMode);
							for (int i = 1; i < n_slices; i++) {
								contents.append(' ').append(module.generatedName(false, "so", "_part_"+i));
							}
						}
					}
				}
			}
			if (gnuMake && userSourcesRegular) {
				contents.append(" $(USER_SOURCES:.cc=.so)");
			} else {
				StringBuilder sharedObjectName;
				for (final UserStruct user : userFiles) {
					if (user.getDirectory() == null || !centralStorage) {
						sharedObjectName = user.specialName("so");
						if (sharedObjectName != null) {
							contents.append(' ').append(sharedObjectName);
						}
					}
				}
			}
		}

		if (centralStorage) {
			contents.append("\n\n");
			contents.append("# Object files of central project(s) that are needed for the ");
			contents.append("executable test suite:\n");
			contents.append("BASE_OBJECTS =");
			if (gnuMake) {
				contents.append(" $(BASE_GENERATED_SOURCES:.cc=.o)");
			} else {
				for (final ModuleStruct module : ttcn3Modules) {
					if (module.getDirectory() != null) {
						contents.append(' ').append(module.generatedName(true, "o"));
						if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
							contents.append(' ').append(module.generatedName(true, "o", "_seq"));
							contents.append(' ').append(module.generatedName(true, "o", "_seqof"));
							contents.append(' ').append(module.generatedName(true, "o", "_set"));
							contents.append(' ').append(module.generatedName(true, "o", "_setof"));
							contents.append(' ').append(module.generatedName(true, "o", "_union"));
						} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
						} else {
							final int n_slices = Integer.parseInt(codeSplittingMode);
							for (int i = 1; i < n_slices; i++) {
								contents.append(' ').append(module.generatedName(true, "o", "_part_"+i));
							}
						}
					}
				}
				if (preprocess) {
					for (final ModuleStruct module : ttcnppModules) {
						if (module.getDirectory() != null) {
							contents.append(' ').append(module.generatedName(true, "o"));
							if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
								contents.append(' ').append(module.generatedName(true, "o", "_seq"));
								contents.append(' ').append(module.generatedName(true, "o", "_seqof"));
								contents.append(' ').append(module.generatedName(true, "o", "_set"));
								contents.append(' ').append(module.generatedName(true, "o", "_setof"));
								contents.append(' ').append(module.generatedName(true, "o", "_union"));
							} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
							} else {
								final int n_slices = Integer.parseInt(codeSplittingMode);
								for (int i = 1; i < n_slices; i++) {
									contents.append(' ').append(module.generatedName(true, "o", "_part_"+i));
								}
							}
						}
					}
				}
				for (final ModuleStruct module : asn1modules) {
					if (module.getDirectory() != null) {
						contents.append(' ').append(module.generatedName(true, "o"));
						if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
							contents.append(' ').append(module.generatedName(true, "o", "_seq"));
							contents.append(' ').append(module.generatedName(true, "o", "_seqof"));
							contents.append(' ').append(module.generatedName(true, "o", "_set"));
							contents.append(' ').append(module.generatedName(true, "o", "_setof"));
							contents.append(' ').append(module.generatedName(true, "o", "_union"));
						} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
						} else {
							final int n_slices = Integer.parseInt(codeSplittingMode);
							for (int i = 1; i < n_slices; i++) {
								contents.append(' ').append(module.generatedName(true, "o", "_part_"+i));
							}
						}
					}
				}
			}
			if (gnuMake && baseUserSourcesRegular) {
				contents.append(" $(BASE_USER_SOURCES:.cc=.o)");
			} else {
				StringBuilder objectName;
				for (final UserStruct user : userFiles) {
					if (user.getDirectory() != null) {
						objectName = user.objectName();
						if (objectName != null) {
							contents.append(' ').append(objectName);
						}
					}
				}
			}
			if (dynamicLinking) {
				contents.append("\n\n");
				contents.append("# Shared object files of central project(s) that are needed for the ");
				contents.append("executable test suite:\n");
				contents.append("BASE_SHARED_OBJECTS =");
				if (gnuMake) {
					contents.append(" $(BASE_GENERATED_SOURCES:.cc=.so)");
				} else {
					for (final ModuleStruct module : ttcn3Modules) {
						if (module.getDirectory() != null) {
							contents.append(' ').append(module.generatedName(true, "so"));
							if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
								contents.append(' ').append(module.generatedName(true, "so", "_seq"));
								contents.append(' ').append(module.generatedName(true, "so", "_seqof"));
								contents.append(' ').append(module.generatedName(true, "so", "_set"));
								contents.append(' ').append(module.generatedName(true, "so", "_setof"));
								contents.append(' ').append(module.generatedName(true, "so", "_union"));
							} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
							} else {
								final int n_slices = Integer.parseInt(codeSplittingMode);
								for (int i = 1; i < n_slices; i++) {
									contents.append(' ').append(module.generatedName(true, "so", "_part_"+i));
								}
							}
						}
					}
					if (preprocess) {
						for (final ModuleStruct module : ttcnppModules) {
							if (module.getDirectory() != null) {
								contents.append(' ').append(module.generatedName(true, "so"));
								if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
									contents.append(' ').append(module.generatedName(true, "so", "_seq"));
									contents.append(' ').append(module.generatedName(true, "so", "_seqof"));
									contents.append(' ').append(module.generatedName(true, "so", "_set"));
									contents.append(' ').append(module.generatedName(true, "so", "_setof"));
									contents.append(' ').append(module.generatedName(true, "so", "_union"));
								} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
								} else {
									final int n_slices = Integer.parseInt(codeSplittingMode);
									for (int i = 1; i < n_slices; i++) {
										contents.append(' ').append(module.generatedName(true, "so", "_part_"+i));
									}
								}
							}
						}
					}
					for (final ModuleStruct module : asn1modules) {
						if (module.getDirectory() != null) {
							contents.append(' ').append(module.generatedName(true, "so"));
							if (GeneralConstants.TYPE.equals(codeSplittingMode)) {
								contents.append(' ').append(module.generatedName(true, "so", "_seq"));
								contents.append(' ').append(module.generatedName(true, "so", "_seqof"));
								contents.append(' ').append(module.generatedName(true, "so", "_set"));
								contents.append(' ').append(module.generatedName(true, "so", "_setof"));
								contents.append(' ').append(module.generatedName(true, "so", "_union"));
							} else if (GeneralConstants.NONE.equals(codeSplittingMode)) {
							} else {
								final int n_slices = Integer.parseInt(codeSplittingMode);
								for (int i = 1; i < n_slices; i++) {
									contents.append(' ').append(module.generatedName(true, "so", "_part_"+i));
								}
							}
						}
					}
				}
				if (gnuMake && baseUserSourcesRegular) {
					contents.append(" $(BASE_USER_SOURCES:.cc=.so)");
				} else {
					for (final UserStruct user : userFiles) {
						if (user.getDirectory() != null) {
							final StringBuilder sharedObjectName = user.specialName("so");
							if (sharedObjectName != null) {
								contents.append(' ').append(sharedObjectName);
							}
						}
					}
				}
			}
		}

		if (incrementalDependencyRefresh) {
			contents.append("\n\n");
			contents.append("# Dependency files of this project that are needed for the executable test suite:\n");
			contents.append("DEPFILES =");
			contents.append(" $(USER_OBJECTS:.o=.d) $(GENERATED_OBJECTS:.o=.d)");
			// USER_OBJECTS is first because GNU make processes
			// included makefiles backwards.
			// This ensures that the compiler is run and cc/hh files
			// are created
			// before the dep.files of USE_SOURCES are created.
		}

		contents.append("\n\n");
		contents.append("# Other files of the project (Makefile, configuration files, etc.)\n");
		contents.append("# that will be added to the archived source files:\n");
		contents.append("OTHER_FILES =");
		for (final OtherFileStruct file : otherFiles) {
			if (!usingSymbolicLinks && file.getOriginalLocation() != null) {
				contents.append(' ').append(file.getOriginalLocation());
			} else {
				contents.append(' ').append(file.name(workingDirectory, useAbsolutePathNames));
			}
		}

		contents.append("\n\n");
		contents.append("# The name of the executable test suite:\n");
		contents.append("EXECUTABLE = ").append(etsName).append('\n');
		contents.append("LIBRARY = " + getLibraryName() + '\n');
		contents.append("\nTARGET = " + (library ? "$(LIBRARY)" : "$(EXECUTABLE)"));

		final String zlib = useCrossCompilation ? "-lz" : "";
		final String rmCommand = gnuMake ? "$(RM)" : "rm -f";

		contents.append("\n\n");
		contents.append("#\n");
		contents.append("# Do not modify these unless you know what you are doing...\n");
		contents.append("# Platform specific additional libraries:\n");
		contents.append("#\n");
		contents.append("SOLARIS_LIBS = -lsocket -lnsl -lxml2 ").append(zlib);
		if (USAGE_STATS) {
			contents.append(" -lresolv");
		}
		for (final IProject reachableProject : reachableProjects) {
			final String[] optionList = PlatformSpecificLibrariesOptionsData.getPlatformSpecificLibraries(reachableProject, "Solaris");
			for (final String option : optionList) {
				contents.append(" -l").append(option);
			}
		}
		contents.append('\n');
		contents.append("SOLARIS8_LIBS = -lsocket -lnsl -lxml2 ").append(zlib);
		if (USAGE_STATS) {
			contents.append(" -lresolv");
		}
		for (final IProject reachableProject : reachableProjects) {
			final String[] optionList = PlatformSpecificLibrariesOptionsData.getPlatformSpecificLibraries(reachableProject, "Solaris8");
			for (final String option : optionList) {
				contents.append(" -l").append(option);
			}
		}
		contents.append('\n');
		contents.append("LINUX_LIBS = -lxml2 ").append(zlib);
		if (USAGE_STATS) {
			contents.append(" -lpthread -lrt");
		}
		for (final IProject reachableProject : reachableProjects) {
			final String[] optionList = PlatformSpecificLibrariesOptionsData.getPlatformSpecificLibraries(reachableProject, "Linux");
			for (final String option : optionList) {
				contents.append(" -l").append(option);
			}
		}
		contents.append('\n');
		contents.append("FREEBSD_LIBS = -lxml2 ").append(zlib);
		for (final IProject reachableProject : reachableProjects) {
			final String[] optionList = PlatformSpecificLibrariesOptionsData.getPlatformSpecificLibraries(reachableProject, "FreeBSD");
			for (final String option : optionList) {
				contents.append(" -l").append(option);
			}
		}
		contents.append('\n');
		contents.append("WIN32_LIBS = -lxml2 ").append(zlib);
		for (final IProject reachableProject : reachableProjects) {
			final String[] optionList = PlatformSpecificLibrariesOptionsData.getPlatformSpecificLibraries(reachableProject, "Win32");
			for (final String option : optionList) {
				contents.append(" -l").append(option);
			}
		}
		contents.append("\n\n");
		contents.append("#\n");
		contents.append("# Rules for building the executable...\n");
		contents.append("#\n\n");
		contents.append("all: $(TARGET) ;\n\n");
		contents.append("executable: $(EXECUTABLE) ;\n\n");
		contents.append("library: $(LIBRARY) ;\n\n");
		contents.append("objects: $(OBJECTS) compile;\n\n");
		if (dynamicLinking) {
			contents.append("shared_objects: $(SHARED_OBJECTS) ;\n\n");
		}

		final StringBuilder allObjects = new StringBuilder();
		if (dynamicLinking) {
			allObjects.append("$(SHARED_OBJECTS)");
			if (centralStorage) {
				allObjects.append(" $(BASE_SHARED_OBJECTS)");
			}
		} else {
			allObjects.append("$(OBJECTS)");
			if (centralStorage) {
				allObjects.append(" $(BASE_OBJECTS)");
			}
		}

		appendExecutableTarget(contents, allObjects.toString(), externalLibrariesDisabled);

		contents.append("$(LIBRARY): " + allObjects + '\n');
		if (dynamicLinking) {
			contents.append("\t$(CXX) -shared -o $@ " + allObjects + "\n\n");
		} else {
			contents.append("\t$(AR) -r $(ARFLAGS) $(LIBRARY) " + allObjects + "\n\n");
		}

		if (dynamicLinking) {
			contents.append("%.so: %.o\n");
			contents.append("\t$(CXX) -shared -o $@ $<\n\n");
		}

		if (!usingSymbolicLinks) {
			StringBuilder objectName;
			for (final UserStruct user : userFiles) {
				objectName = user.objectName();
				if (objectName != null) {
					contents.append(objectName + " : ");
					if (user.getOriginalSourceLocation() != null) {
						contents.append(' ').append(user.getOriginalSourceLocation());
					} else {
						contents.append(' ').append(user.sourceName());
					}
					contents.append('\n');
					contents.append("\t$(CXX) -c $(CPPFLAGS) $(CXXFLAGS) -o $@ $<\n\n");
				}
			}
		}

		contents.append(".cc.o .c.o:\n");
		contents.append("\t$(CXX) -c $(CPPFLAGS) $(CXXFLAGS) -o $@ $<\n\n");

		if (incrementalDependencyRefresh) {
			if (!usingSymbolicLinks) {
				StringBuilder depName;
				for (final UserStruct user : userFiles) {
					depName = user.specialName("d");
					if (depName != null) {
						contents.append(depName + " : ");
						if (user.getOriginalSourceLocation() != null) {
							contents.append(' ').append(user.getOriginalSourceLocation());
						} else {
							contents.append(' ').append(user.sourceName());
						}
						contents.append('\n');
						contents.append("\t@echo Creating dependency file for '$<'; set -e; \\\n");
						contents.append("\t$(CXX) $(CXXDEPFLAGS) $(CPPFLAGS) $(CXXFLAGS) $< \\\n");
						contents.append("\t| sed 's/\\($*\\)\\.o[ :]*/\\1.o $@ : /g' > $@; \\\n");
						contents.append("\t[ -s $@ ] || rm -f $@\n\n");
					}
				}
			}

			contents.append(".cc.d .c.d:\n");
			contents.append("\t@echo Creating dependency file for '$<'; set -e; \\\n");
			contents.append("\t$(CXX) $(CXXDEPFLAGS) $(CPPFLAGS) $(CXXFLAGS) $< \\\n");
			contents.append("\t| sed 's/\\($*\\)\\.o[ :]*/\\1.o $@ : /g' > $@; \\\n");
			contents.append("\t[ -s $@ ] || rm -f $@\n\n");
			/*
			 * "set -e" causes bash to exit the script if any
			 * statement returns nonzero (failure). The sed line
			 * transforms the first line of the dependency from
			 * "x.o: x.cc" to "x.o x.d: x.cc", making the dependency
			 * file depend on the source and headers. [ -s x.d ]
			 * checks that the generated dependency is not empty;
			 * otherwise it gets deleted.
			 */
		}

		if (preprocess) {
			if (!usingSymbolicLinks) {
				for (final ModuleStruct module : ttcnppModules) {
					final StringBuilder depName = module.preprocessedName(true);
					if (depName != null) {
						contents.append(depName + " : ");
						if (module.getOriginalLocation() != null) {
							contents.append(' ').append(module.getOriginalLocation());
						} else {
							contents.append(' ').append(module.getFileName());
						}
						contents.append(" $(TTCN3_INCLUDES) \n");
						contents.append("\t$(CPP) -x c -nostdinc $(CPPFLAGS_TTCN3) $< $@\n\n");
					}
				}
			}

			contents.append("%.ttcn: %.ttcnpp $(TTCN3_INCLUDES)\n");
			contents.append("\t$(CPP) -x c -nostdinc $(CPPFLAGS_TTCN3) $< $@\n\n");
			contents.append("preprocess: $(PREPROCESSED_TTCN3_MODULES) ;\n\n");
		}
		if (centralStorage) {
			boolean isFirst = true;
			contents.append("$(GENERATED_SOURCES) $(GENERATED_HEADERS): compile-all compile");
			for (final BaseDirectoryStruct dir : baseDirectories) {
				if (dir.isHasModules()) {
					if (isFirst) {
						contents.append(" \\\n");
						isFirst = false;
					} else {
						contents.append(' ');
					}
					if (!".".equals(dir.name().toString())) {
						contents.append(dir.name()).append("/compile");
					}
				}
			}
			if (preprocess) {
				contents.append('\n');
				contents.append("\t@if [ ! -f $@ ]; then ").append(rmCommand).append(" compile-all; $(MAKE) compile-all; fi\n");
				contents.append('\n');
				contents.append("check: $(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
				contents.append("$(PREPROCESSED_TTCN3_MODULES) $(BASE_PREPROCESSED_TTCN3_MODULES) ");
				contents.append("\\\n");
				contents.append("$(ASN1_MODULES) $(BASE_ASN1_MODULES)\n");
				contents.append("\t$(TTCN3_DIR)/bin/compiler -s $(COMPILER_FLAGS) ");
				if (gnuMake) {
					contents.append("$^");
				} else {
					contents.append("\\\n");
					contents.append("\t$(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
					contents.append("\t$(PREPROCESSED_TTCN3_MODULES) ");
					contents.append("$(BASE_PREPROCESSED_TTCN3_MODULES) \\\n");
					contents.append("\t$(ASN1_MODULES) $(BASE_ASN1_MODULES)");
				}
				contents.append("\n\n");
				contents.append("port: $(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
				contents.append("$(PREPROCESSED_TTCN3_MODULES) $(BASE_PREPROCESSED_TTCN3_MODULES) ");
				contents.append("\\\n");
				contents.append("$(ASN1_MODULES) $(BASE_ASN1_MODULES)\n");
				contents.append("\t$(TTCN3_DIR)/bin/compiler -t $(COMPILER_FLAGS) ");
				if (gnuMake) {
					contents.append("$^");
				} else {
					contents.append("\\\n");
					contents.append("\t$(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
					contents.append("\t$(PREPROCESSED_TTCN3_MODULES) ");
					contents.append("$(BASE_PREPROCESSED_TTCN3_MODULES)");
				}
				contents.append("\n\n");
				contents.append("compile: $(TTCN3_MODULES) $(PREPROCESSED_TTCN3_MODULES) ");
				contents.append("$(ASN1_MODULES)\n");
				contents.append("\t$(TTCN3_DIR)/bin/compiler $(COMPILER_FLAGS) \\\n");
				contents.append("\t$(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
				contents.append("\t$(PREPROCESSED_TTCN3_MODULES) ");
				contents.append("$(BASE_PREPROCESSED_TTCN3_MODULES) \\\n");
				contents.append("\t$(ASN1_MODULES) $(BASE_ASN1_MODULES) - $?\n");
				contents.append("\ttouch $@\n");
				contents.append('\n');
				contents.append("compile-all: $(BASE_TTCN3_MODULES) ");
				contents.append("$(BASE_PREPROCESSED_TTCN3_MODULES) \\\n");
				contents.append("$(BASE_ASN1_MODULES)\n");
				contents.append("\t$(MAKE) preprocess\n");
				contents.append("\t$(TTCN3_DIR)/bin/compiler $(COMPILER_FLAGS) \\\n");
				contents.append("\t$(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
				contents.append("\t$(PREPROCESSED_TTCN3_MODULES) $(BASE_PREPROCESSED_TTCN3_MODULES) ");
				contents.append("\\\n");
				contents.append("\t$(ASN1_MODULES) $(BASE_ASN1_MODULES) \\\n");
				contents.append("\t- $(TTCN3_MODULES) $(PREPROCESSED_TTCN3_MODULES) $(ASN1_MODULES)\n");
				contents.append("\ttouch $@ compile\n\n");
			} else {
				contents.append('\n');
				contents.append("\t@if [ ! -f $@ ]; then ").append(rmCommand).append(" compile-all; $(MAKE) compile-all; fi\n");
				contents.append('\n');
				contents.append("check: $(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
				contents.append("$(ASN1_MODULES) $(BASE_ASN1_MODULES)\n");
				contents.append("\t$(TTCN3_DIR)/bin/compiler -s $(COMPILER_FLAGS) ");
				if (gnuMake) {
					contents.append("$^");
				} else {
					contents.append("\\\n");
					contents.append("\t$(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
					contents.append("\t$(ASN1_MODULES) $(BASE_ASN1_MODULES)");
				}
				contents.append("\n\n");
				contents.append("port: $(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
				contents.append("$(ASN1_MODULES) $(BASE_ASN1_MODULES)\n");
				contents.append("\t$(TTCN3_DIR)/bin/compiler -t $(COMPILER_FLAGS) ");
				if (gnuMake) {
					contents.append("$^");
				} else {
					contents.append("\\\n");
					contents.append("\t$(TTCN3_MODULES) $(BASE_TTCN3_MODULES)");
				}
				contents.append("\n\n");
				contents.append("compile: $(TTCN3_MODULES) $(ASN1_MODULES)\n");
				contents.append("\t$(TTCN3_DIR)/bin/compiler $(COMPILER_FLAGS) \\\n");
				contents.append("\t$(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
				contents.append("\t$(ASN1_MODULES) $(BASE_ASN1_MODULES) \\\n");
				contents.append("\t- $?\n");
				contents.append("\ttouch $@\n");
				contents.append('\n');
				contents.append("compile-all: $(BASE_TTCN3_MODULES) $(BASE_ASN1_MODULES)\n");
				contents.append("\t$(TTCN3_DIR)/bin/compiler $(COMPILER_FLAGS) \\\n");
				contents.append("\t$(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
				contents.append("\t$(ASN1_MODULES) $(BASE_ASN1_MODULES) \\\n");
				contents.append("\t- $(TTCN3_MODULES) $(ASN1_MODULES)\n");
				contents.append("\ttouch $@ compile\n\n");
			}
			for (final BaseDirectoryStruct dir : baseDirectories) {
				if (dir.isHasModules() && !".".equals(dir.name().toString())) {
					contents.append(dir.name()).append("/compile:");
					if (allProjectsUseSymbolicLinks) {
						for (final ModuleStruct module : ttcn3Modules) {
							final String dirName = module.getDirectory();
							if (dirName != null && dir.getDirectoryName().equals(dirName)) {
								contents.append(' ').append(dir.name()).append('/').append(module.getFileName());
							}
						}
						for (final ModuleStruct module : ttcnppModules) {
							final String dirName = module.getDirectory();
							if (dirName != null && dir.getDirectoryName().equals(dirName)) {
								contents.append(' ').append(dir.name()).append('/').append(module.getFileName());
							}
						}
						for (final ModuleStruct module : asn1modules) {
							final String dirName = module.getDirectory();
							if (dirName != null && dir.getDirectoryName().equals(dirName)) {
								contents.append(' ').append(dir.name()).append('/').append(module.getFileName());
							}
						}
					} else {
						for (final ModuleStruct module : ttcn3Modules) {
							final String dirName = module.getDirectory();
							if (dirName != null && dir.getDirectoryName().equals(dirName)) {
								contents.append(' ').append(module.getOriginalLocation());
							}
						}
						for (final ModuleStruct module : ttcnppModules) {
							final String dirName = module.getDirectory();
							if (dirName != null && dir.getDirectoryName().equals(dirName)) {
								contents.append(' ').append(module.getOriginalLocation());
							}
						}
						for (final ModuleStruct module : asn1modules) {
							final String dirName = module.getDirectory();
							if (dirName != null && dir.getDirectoryName().equals(dirName)) {
								contents.append(' ').append(module.getOriginalLocation());
							}
						}
					}
					contents.append('\n');
					contents.append("\t@echo 'Central directory ").append(dir.originalName()).append(" is not up-to-date!'\n");
					contents.append("\t@exit 2\n\n");
				}
			}

			contents.append("browserdata.dat: $(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
			if (preprocess) {
				contents.append("$(PREPROCESSED_TTCN3_MODULES) ");
				contents.append("$(BASE_PREPROCESSED_TTCN3_MODULES) \\\n");
			}
			contents.append("$(ASN1_MODULES) $(BASE_ASN1_MODULES)\n");
			contents.append("\t$(TTCN3_DIR)/bin/compiler -B -s $(COMPILER_FLAGS) ");
			if (gnuMake) {
				contents.append("$^");
			} else {
				contents.append("\\\n");
				contents.append("\t$(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
				if (preprocess) {
					contents.append("\t$(PREPROCESSED_TTCN3_MODULES) ");
					contents.append("$(BASE_PREPROCESSED_TTCN3_MODULES) \\\n");
				}
				contents.append("\t$(BASE_ASN1_MODULES) $(ASN1_MODULES)");
			}
			contents.append("\n\n");
		} else {
			contents.append("$(GENERATED_SOURCES) $(GENERATED_HEADERS): compile\n");
			contents.append("\t@if [ ! -f $@ ]; then ").append(rmCommand).append(" compile; $(MAKE) compile; fi\n\n");
			contents.append("check: $(TTCN3_MODULES) ");
			if (preprocess) {
				contents.append("$(PREPROCESSED_TTCN3_MODULES) ");
			}
			contents.append("$(ASN1_MODULES)\n");
			contents.append("\t$(TTCN3_DIR)/bin/compiler -s $(COMPILER_FLAGS) ");
			if (gnuMake) {
				contents.append("$^");
			} else {
				contents.append("\\\n");
				contents.append("\t$(TTCN3_MODULES) $(PREPROCESSED_TTCN3_MODULES) $(ASN1_MODULES)");
			}
			contents.append("\n\n");
			contents.append("port: $(TTCN3_MODULES) ");
			if (preprocess) {
				contents.append("$(PREPROCESSED_TTCN3_MODULES) ");
			}
			contents.append("$(ASN1_MODULES)\n");
			contents.append("\t$(TTCN3_DIR)/bin/compiler -t $(COMPILER_FLAGS) ");
			if (gnuMake) {
				contents.append("$^");
			} else {
				contents.append("\\\n");
				contents.append("\t$(TTCN3_MODULES) $(PREPROCESSED_TTCN3_MODULES)");
			}
			contents.append("\n\n");
			contents.append("compile: $(TTCN3_MODULES) ");
			if (preprocess) {
				contents.append("$(PREPROCESSED_TTCN3_MODULES) ");
			}
			contents.append("$(ASN1_MODULES)\n");
			contents.append("\t$(TTCN3_DIR)/bin/compiler $(COMPILER_FLAGS) ");
			if (gnuMake) {
				contents.append("$^");
			} else {
				contents.append("\\\n");
				contents.append("\t$(TTCN3_MODULES) ");
				if (preprocess) {
					contents.append("$(PREPROCESSED_TTCN3_MODULES) ");
				}
				contents.append("$(ASN1_MODULES)");
			}
			contents.append(" - $?\n");
			contents.append("\ttouch $@\n");
			contents.append('\n');
			contents.append("browserdata.dat: $(TTCN3_MODULES) ");
			if (preprocess) {
				contents.append("$(PREPROCESSED_TTCN3_MODULES) ");
			}
			contents.append("$(ASN1_MODULES)\n");
			contents.append("\t$(TTCN3_DIR)/bin/compiler -B -s $(COMPILER_FLAGS) ");
			if (gnuMake) {
				contents.append("$^");
			} else {
				contents.append("\\\n");
				contents.append("\t$(TTCN3_MODULES) ");
				if (preprocess) {
					contents.append("$(PREPROCESSED_TTCN3_MODULES) ");
				}
				contents.append("$(ASN1_MODULES)");
			}
			contents.append("\n\n");
		}

		contents.append("clean:\n");
		contents.append("\t-").append(rmCommand).append(" $(EXECUTABLE) $(OBJECTS) $(LIBRARY) $(GENERATED_HEADERS)");
		contents.append(" \\\n");
		contents.append("\t$(GENERATED_SOURCES) ");
		if (dynamicLinking) {
			contents.append("$(SHARED_OBJECTS) ");
		}
		if (preprocess) {
			contents.append("$(PREPROCESSED_TTCN3_MODULES) ");
		}
		contents.append("compile");
		if (centralStorage) {
			contents.append(" compile-all");
		}
		if (incrementalDependencyRefresh) {
			contents.append(" $(DEPFILES)");
		}
		contents.append(" \\\n");
		contents.append("\tbrowserdata.dat tags *.log");
		contents.append("\n\n");
		contents.append("dep: $(GENERATED_SOURCES) $(USER_SOURCES)");
		if (incrementalDependencyRefresh) {
			// nothing to do for "dep" as such
			contents.append(" $(DEPFILES) ;\n\n");
			contents.append("ifeq ($(filter clean check port compile archive,$(MAKECMDGOALS)),)\n");
			if (preprocess) {
				// Prevent nasty infinite make loop
				contents.append("ifeq ($(findstring preprocess,$(MAKECMDGOALS)),)\n");
			}
			contents.append("-include $(DEPFILES)\n");
			if (preprocess) {
				contents.append("endif\n");
			}
			contents.append("endif\n\n");
		} else {
			contents.append("\n\tmakedepend $(CPPFLAGS) -DMAKEDEPEND_RUN ");
			if (gnuMake) {
				contents.append("$^");
			} else {
				contents.append("$(GENERATED_SOURCES) $(USER_SOURCES)");
			}
		}
		contents.append("\n\n");
		contents.append("archive:\n");
		contents.append("\tmkdir -p $(ARCHIVE_DIR)\n");
		contents.append("\ttar -cvhf - ");
		if (centralStorage) {
			contents.append("$(TTCN3_MODULES) $(BASE_TTCN3_MODULES) \\\n");
			if (preprocess) {
				contents.append("\t$(TTCN3_PP_MODULES) $(BASE_TTCN3_PP_MODULES) ");
				contents.append("$(TTCN3_INCLUDES)\\\n");
			}
			contents.append("\t$(ASN1_MODULES) $(BASE_ASN1_MODULES) \\\n");
			contents.append("\t$(USER_HEADERS) $(BASE_USER_HEADERS) \\\n");
			contents.append("\t$(USER_SOURCES) $(BASE_USER_SOURCES)");
		} else {
			contents.append("$(TTCN3_MODULES) ");
			if (preprocess) {
				contents.append("$(TTCN3_PP_MODULES) \\\n");
				contents.append("\t$(TTCN3_INCLUDES) ");
			}
			contents.append("$(ASN1_MODULES) \\\n");
			contents.append("\t$(USER_HEADERS) $(USER_SOURCES)");
		}
		contents.append(" $(OTHER_FILES) \\\n");
		contents.append("\t| gzip >$(ARCHIVE_DIR)/`basename $(TARGET) .exe`-");
		contents.append("`date '+%y%m%d-%H%M'`.tgz\n\n");
		contents.append("diag:\n");
		contents.append("\t$(TTCN3_DIR)/bin/compiler -v 2>&1\n");
		contents.append("\t$(TTCN3_DIR)/bin/mctr_cli -v 2>&1\n");
		contents.append("\t$(CXX) -v 2>&1\n");
		if (!dynamicLinking) {
			contents.append("\t$(AR) -V 2>&1\n");
		}
		contents.append("\t@echo TTCN3_DIR=$(TTCN3_DIR)\n");
		contents.append("\t@echo OPENSSL_DIR=$(OPENSSL_DIR)\n");
		contents.append("\t@echo XMLDIR=$(XMLDIR)\n");
		contents.append("\t@echo PLATFORM=$(PLATFORM)\n\n");
		contents.append("#\n");
		contents.append("# Add your rules here if necessary...\n");
		contents.append("#\n\n");

		final String makefileName = "Makefile";
		try {
			if (project.getLocation().isPrefixOf(workingDirectoryPath)) {
				final int matchingSegments = project.getLocation().matchingFirstSegments(workingDirectoryPath);
				final IPath samplePath = workingDirectoryPath.removeFirstSegments(matchingSegments);
				final IFolder folder = project.getFolder(samplePath);
				if (!folder.isAccessible()) {
					ResourceUtils.refreshResources(Arrays.asList(folder));
				}

				final IFile sampleMakefile = project.getFile(samplePath.append("/" + makefileName));
				if (needsUpdate(sampleMakefile, contents.toString())) {
					sampleMakefile.refreshLocal(IResource.DEPTH_ZERO, null);
					if (sampleMakefile.exists()) {
						sampleMakefile.setContents(new ByteArrayInputStream(contents.toString().getBytes()), IResource.FORCE
								| IResource.KEEP_HISTORY, null);
					} else {
						sampleMakefile.create(new ByteArrayInputStream(contents.toString().getBytes()), IResource.FORCE, null);
					}

					ResourceUtils.refreshResources(Arrays.asList(sampleMakefile));
					sampleMakefile.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
				}
			} else {
				final IPath makefilepath = workingDirectoryPath.append(makefileName);

				BufferedOutputStream out = null;
				try {
					out = new BufferedOutputStream(new FileOutputStream(makefilepath.toOSString()));
					out.write(contents.toString().getBytes());
				} catch (FileNotFoundException e) {
					ErrorReporter.logExceptionStackTrace(e);
				} catch (IOException e) {
					ErrorReporter.logExceptionStackTrace(e);
				} finally {
					IOUtils.closeQuietly(out);
				}
			}
		} catch (CoreException e) {
			ErrorReporter.logExceptionStackTrace(e);
		}
	}

	private static boolean needsUpdate(final IFile file, final String content) throws CoreException {
		if (!file.isAccessible()) {
			return true;
		}

		boolean result = true;
		final InputStream filestream = file.getContents();
		final BufferedInputStream bufferedFile = new BufferedInputStream(filestream);
		final InputStream contentStream = new ByteArrayInputStream( content.getBytes() );
		final BufferedInputStream bufferedOutput = new BufferedInputStream(contentStream);
		try {
			int read1 = bufferedFile.read();
			int read2 = bufferedOutput.read();
			while (read1 != -1 && read1 == read2) {
				read1 = bufferedFile.read();
				read2 = bufferedOutput.read();
			}

			result = read1 != read2;
			bufferedFile.close();
			bufferedOutput.close();
		} catch (IOException exception) {
			return true;
		}

		return result;
	}

	public void gatherInformation() {
		centralStorage = false;
		try {
			final MakefileGeneratorVisitor visitor = new MakefileGeneratorVisitor(this, project);
			project.accept(visitor);
			centralStorage = !visitor.getCentralStorages().isEmpty();
		} catch (CoreException e) {
			ErrorReporter.logExceptionStackTrace(e);
			return;
		}

		final List<IProject> tempReachableProjects = ProjectBasedBuilder.getProjectBasedBuilder(project).getAllReachableProjects();
		reachableProjects = new ArrayList<IProject>(tempReachableProjects.size());
		boolean foundClosedProject = false;
		for (final IProject reachableProject : tempReachableProjects) {
			if (reachableProject.isAccessible()) {
				reachableProjects.add(reachableProject);
			} else {
				final StringBuilder builder = new StringBuilder("The project `" + reachableProject.getName()
						+ "' (reachable from project `" + project.getName()
						+ "') is not accessible.");
				final IProject[] referencingProjects = reachableProject.getReferencingProjects();
				if (referencingProjects != null && referencingProjects.length > 0) {
					builder.append(" The project `").append(reachableProject.getName()).append("' is referenced directly by");
					for (final IProject referencingProject : referencingProjects) {
						builder.append(" `").append(referencingProject.getName()).append('\'');
					}
				}
				ErrorReporter.logError(builder.toString());
				foundClosedProject = true;
			}
		}

		for (final IProject reachableProject : reachableProjects) {
			if (!reachableProject.equals(project)) {
				centralStorage = true;
				try {
					reachableProject.accept(new MakefileGeneratorVisitor(this, reachableProject));
				} catch (CoreException e) {
					ErrorReporter.logExceptionStackTrace(e);
					return;
				}
			}
		}
	
		if (foundClosedProject) {
			ErrorReporter.parallelErrorDisplayInMessageDialog("Error during Makefile generation",
					"A makefile can not be generated for project " + project.getName() + "\n"
							+ "Some of the projects referenced by it are not accessible.\n"
							+ "Please check the error log for more details.");
			return;
		}
	
		try {
			codeSplittingMode = project.getPersistentProperty(new QualifiedName(ProjectBuildPropertyData.QUALIFIER,
					MakefileCreationData.CODE_SPLITTING_PROPERTY));
			if (codeSplittingMode == null || GeneralConstants.NONE.equals(codeSplittingMode)) {
				codeSplittingMode = GeneralConstants.NONE;
			} else if (!GeneralConstants.TYPE.equals(codeSplittingMode)) {
				try {
					Integer.parseInt(codeSplittingMode);
				} catch (NumberFormatException ex) {
					ErrorReporter.logExceptionStackTrace(ex);
					codeSplittingMode = GeneralConstants.NONE;
				}
			}
		} catch (CoreException e) {
			ErrorReporter.logExceptionStackTrace(e);
			return;
		}
	
		// Add the Makefile to the other files, as it will belong there
		// once we create it
		final OtherFileStruct otherFile = new OtherFileStruct(null, null, "Makefile");
		otherFiles.add(otherFile);
	}
	
	private StringBuilder appendExecutableTarget(final StringBuilder contents, final String allObjects, final boolean externalLibrariesDisabled) {
		final boolean reportDebugInformation = Platform.getPreferencesService().getBoolean(ProductConstants.PRODUCT_ID_DESIGNER,
				PreferenceConstants.DISPLAYDEBUGINFORMATION, false, null);
		final List<IProject> referencedProjects = ProjectBasedBuilder.getProjectBasedBuilder(project).getAllReachableProjects();
		if (dynamicLinking && library) {
			contents.append("$(EXECUTABLE): $(LIBRARY)\n");
			contents.append("\t$(CXX) $(LDFLAGS) -o $@ $(LIBRARY)");
		} else {
			contents.append("$(EXECUTABLE): " + allObjects).append('\n');
			contents.append("\t$(CXX) $(LDFLAGS) -o $@ ");
			if(dynamicLinking && !Platform.OS_SOLARIS.equals(Platform.getOS())) {
				contents.append("-Wl,--no-as-needed ");
			}
			contents.append(gnuMake ? "$^" : allObjects);

			for (final IProject referencedProject : referencedProjects) {
				final String[] optionList = LinkerLibrariesOptionsData.getAdditionalObjects(referencedProject);
				if (optionList.length > 0) {
					final IPath location = referencedProject.getLocation();
					if (location == null) {
						ErrorReporter.logError("The project `" + referencedProject.getName()
								+ "' is not located in the local file system."
								+ " The additional object files to link against,"
								+ " set for it will not be generated into the Makefile");
					} else {
						final String tempProjectLocation = location.toOSString();
						for (String option : optionList) {
							final StringBuilder output = new StringBuilder();
							option = TITANPathUtilities.resolvePathURIForMakefile(option, 
									tempProjectLocation,reportDebugInformation, output);
							contents.append(' ').append(option);
							TITANDebugConsole.println(output);
						}
					}
				}
			}
		}
		contents.append(" \\\n");
		contents.append("\t-L$(TTCN3_");
		if (useCrossCompilation) {
			contents.append("TARGET_");
		}
		contents.append("DIR)/lib");
		if (!externalLibrariesDisabled) {
			contents.append(" -L$(OPENSSL_DIR)/lib -L$(XMLDIR)/lib");
		}

		for (final IProject referencedProject : referencedProjects) {
			if (!referencedProject.isAccessible()) {
				continue;
			}

			final String[] optionList = LinkerLibrariesOptionsData.getLinkerSearchPaths(referencedProject);
			if (optionList.length > 0) {
				final IPath location = referencedProject.getLocation();
				if (location == null) {
					ErrorReporter.logError("The project `"
							+ referencedProject.getName()
							+ "' is not located in the local file system. The extra linker search paths set for it will not be generated into the Makefile");
				} else {
					final String tempProjectLocation = location.toOSString();
					for (String temp : optionList) {
						final StringBuilder output = new StringBuilder();
						temp = TITANPathUtilities.resolvePathURIForMakefile(temp, 
								tempProjectLocation, reportDebugInformation, output);
						contents.append(" -L").append(temp);
						TITANDebugConsole.println(output);
					}
				}
			}
		}

		contents.append(" \\\n");
		contents.append("\t-l$(TTCN3_LIB)");
		contents.append(" -lcrypto");
		if (!referencedProjects.isEmpty()) {
			for (final IProject tempProject : referencedProjects) {
				final String[] optionList = LinkerLibrariesOptionsData.getLinkerLibraries(tempProject);
				for (final String anOptionList : optionList) {
					contents.append(" -l").append(anOptionList);
				}
			}
		}

		contents.append(" \\\n");
		contents.append("\t$($(PLATFORM)_LIBS) \\\n");
		contents.append("\t|| if [ -f $(TTCN3_DIR)/bin/titanver ]; then $(TTCN3_DIR)/bin/titanver ");
		contents.append(gnuMake ? "$^" : allObjects).append("; else : ; fi\n");
		if (asn1modules.isEmpty() && ttcn3Modules.isEmpty() && ttcnppModules.isEmpty()) {
			/*
			 * Just C++ files. compiler will not be run; update the
			 * "compile" marker file here, in case this project is
			 * used as central storage.
			 */
			contents.append("\ttouch compile\n");
		}
		return contents.append('\n');
	}

	private String getLibraryName() {
		if (etsName == null || etsName.length() == 0) {
			return "";
		}
		final String prefix = etsName.endsWith(".exe") ? etsName.substring(0, etsName.length() - ".exe".length()) : etsName;

		return prefix + (dynamicLinking ? "_lib.so" : ".a");
	}

	/**
	 * Collects all of the parameters needed to generate the Makefile.
	 *
	 * @throws CoreException if this method fails. Reasons include:
	 *                       <ul>
	 *                       <li>This project does not exist.</li>
	 *                       <li>This project is not local.</li>
	 *                       <li>This project is a project that is not open.</li>
	 *                       </ul>
	 */
	private void setParameters() throws CoreException {
		final boolean reportDebugInformation = Platform.getPreferencesService().getBoolean(ProductConstants.PRODUCT_ID_DESIGNER,
				PreferenceConstants.DISPLAYDEBUGINFORMATION, false, null);

		workingDirectoryPath = ProjectBasedBuilder.getProjectBasedBuilder(project).getWorkingDirectoryPath(true);
		workingDirectory = workingDirectoryPath.toOSString();
		if (reportDebugInformation) {
			TITANDebugConsole.println(workingDirectory);
		}

		final String productNumber = CompilerVersionInformationCollector.getCompilerProductNumber();
		compilerProductNumber = ProductIdentityHelper.getProductIdentity(productNumber, null);


		gnuMake = ResourceUtils.getBooleanPersistentProperty(project, ProjectBuildPropertyData.QUALIFIER, MakefileCreationData.GNU_MAKE_PROPERTY);

		if (ResourceUtils.getBooleanPersistentProperty(
				project, ProjectBuildPropertyData.QUALIFIER, MakefileCreationData.INCREMENTAL_DEPENDENCY_PROPERTY)) {
			incrementalDependencyRefresh = true;
			if (!gnuMake) {
				TITANConsole.println(INVALID_OPTIONS);
				ErrorReporter.logError("Incremental dependency refresh is only supported if generating GNU makefiles. The GNU make option was turned on.");
				ResourceUtils.setPersistentProperty(project, ProjectBuildPropertyData.QUALIFIER, MakefileCreationData.GNU_MAKE_PROPERTY, true);
			}
		}
		if (ResourceUtils.getBooleanPersistentProperty(
				project, ProjectBuildPropertyData.QUALIFIER, MakefileCreationData.DYNAMIC_LINKING_PROPERTY)) {
			if (!Platform.OS_WIN32.equals(Platform.getOS())) { 
				dynamicLinking = true;
			} else {
				TITANConsole.println(INVALID_OPTIONS);
				ErrorReporter.logError("Could not create Makefile with dynamic linking enabled for project " + project.getName()
						+ " as this is not supported on Windows");
			}
		}

		singleMode = ResourceUtils.getBooleanPersistentProperty(project, ProjectBuildPropertyData.QUALIFIER, MakefileCreationData.SINGLEMODE_PROPERTY);

		useAbsolutePathNames = ResourceUtils.getBooleanPersistentProperty(project, ProjectBuildPropertyData.QUALIFIER, MakefileCreationData.USE_ABSOLUTEPATH_PROPERTY);

		useRuntime2 = ResourceUtils.getBooleanPersistentProperty(project, ProjectBuildPropertyData.QUALIFIER, MakefileCreationData.FUNCTIONTESTRUNTIME_PROPERTY);

		library = MakefileCreationData.DefaultTarget.LIBRARY.toString().equals(
				project.getPersistentProperty(new QualifiedName(ProjectBuildPropertyData.QUALIFIER,
						MakefileCreationData.DEFAULT_TARGET_PROPERTY)));

		usingSymbolicLinks = ProjectBuildPropertyData.useSymbolicLinks(project);

		allProjectsUseSymbolicLinks = usingSymbolicLinks;

		String temp = ResourceUtils.getPersistentProperty(
				project, 
				ProjectBuildPropertyData.QUALIFIER, 
				MakefileCreationData.TARGET_EXECUTABLE_PROPERTY);
		if (temp != null && temp.trim().length() > 0) {
			final StringBuilder output = new StringBuilder();
			etsName = TITANPathUtilities.resolvePathURIForMakefile(
					temp, projectLocation, reportDebugInformation, output);
			TITANDebugConsole.println(output);
		} else {
			temp = project.getName();
			if (Platform.OS_WIN32.equals(Platform.getOS())) {
				etsName = temp + ".exe";
			} else {
				etsName = temp;
			}
		}
	}

	/**
	 * Adds a file to the list of TTCN-3 files.
	 * <p/>
	 * The directory of the file must always point to a working directory,
	 * or a central storage directory.
	 *
	 * @param file      the file to be added.
	 * @param directory in which this file can be found (null if the working
	 *                  directory of the actual project)
	 */
	public void addTTCN3Module(final IFile file, final String directory) {
		final ProjectSourceParser parser = GlobalParser.getProjectSourceParser(file.getProject());
		final String moduleName = parser.containedModuleName(file);
		if (moduleName == null) {
			if (file.isSynchronized(IResource.DEPTH_ZERO)) {
				ErrorReporter.logWarning("file " + file.getFullPath().toOSString() + " is out-of sync with the file system");
			} else {
				ErrorReporter.logWarning("file "
						+ file.getFullPath().toOSString()
						+ " even tough it has ttcn extension is added as on other file since the on-the-fly analyzer was not able to find a valid module inside");
			}
			addOtherFiles(file, directory);
			return;
		}

		final Identifier identifier = new Identifier(Identifier_type.ID_NAME, moduleName);

		ModuleStruct module;
		final IPath fileLocation = file.getLocation();
		if (fileLocation == null) {
			final String originalLocation = directory + File.separatorChar + file.getName();
			module = new ModuleStruct(directory, originalLocation, file.getName(), identifier.getTtcnName());
		} else {
			final String originalLocation = fileLocation.toOSString();
			module = new ModuleStruct(directory, originalLocation, fileLocation.lastSegment(), identifier.getTtcnName());
		}

		module.setRegular(fileLocation != null && "ttcn".equals(file.getFileExtension()) && file.getName().equals(moduleName + ".ttcn"));

		ttcn3Modules.add(module);
	}

	/**
	 * Adds a file to the list of ASN.1 files.
	 * <p/>
	 * The directory of the file must always point to a working directory,
	 * or a central storage directory.
	 *
	 * @param file      the file to be added.
	 * @param directory in which this file can be found (null if the working
	 *                  directory of the actual project)
	 */
	public void addASN1Module(final IFile file, final String directory) {
		final ProjectSourceParser parser = GlobalParser.getProjectSourceParser(file.getProject());
		final String moduleName = parser.containedModuleName(file);
		if (moduleName == null) {
			if (file.isSynchronized(IResource.DEPTH_ZERO)) {
				ErrorReporter.logWarning("file " + file.getFullPath().toOSString() + " is out-of sync with the file system");
			} else {
				ErrorReporter.logWarning("file "
						+ file.getFullPath().toOSString()
						+ " even tough it has asn extension is added as on other file since the on-the-fly analyzer was not able to find a valid module inside");
			}
			addOtherFiles(file, directory);
			return;
		}

		final IPath location = file.getLocation();
		String originalLocation;
		if (location == null) {
			originalLocation = directory + File.separatorChar + file.getName();
		} else {
			originalLocation = location.toOSString();
		}
		final Identifier identifier = new Identifier(Identifier_type.ID_NAME, moduleName);
		final ModuleStruct module = new ModuleStruct(directory, originalLocation, file.getName(), identifier.getAsnName());

		String prefix = file.getName();
		prefix = prefix.replace('_', '-');
		module.setRegular(location != null && "asn".equals(file.getFileExtension()) && prefix.equals(moduleName + ".asn"));

		asn1modules.add(module);
	}

	/**
	 * Adds a file to the list of C/C++ header files.
	 * <p/>
	 * The directory of the file must always point to a working directory,
	 * or a central storage directory.
	 *
	 * @param file      the file to be added.
	 * @param directory in which this file can be found (null if the working
	 *                  directory of the actual project)
	 */
	public void addUserHeaderFile(final IFile file, final String directory) {
		final String name = file.getName();
		final String filePrefix = name.substring(0, name.lastIndexOf('.'));

		for (final UserStruct other : userFiles) {
			if (other.getDirectory() != null && other.getFilePrefix() != null && other.getDirectory().equals(directory)
					&& other.getFilePrefix().equals(filePrefix)) {
				other.setHeaderName(name);
				if (file.getLocation() == null) {
					other.setOriginalHeaderLocation(directory + File.separatorChar + file.getName());
				} else {
					other.setOriginalHeaderLocation(file.getLocation().toOSString());
				}
				other.setHasHHSuffix("hh".equals(file.getFileExtension()));
				return;
			}
		}

		final UserStruct userFile = new UserStruct();
		userFile.setDirectory(directory);
		if (file.getLocation() == null) {
			userFile.setOriginalHeaderLocation(directory + File.separatorChar + file.getName());
		} else {
			userFile.setOriginalHeaderLocation(file.getLocation().toOSString());
		}
		userFile.setFileName(name);
		userFile.setFilePrefix(name.substring(0, name.lastIndexOf('.')));
		userFile.setHeaderName(file.getName());
		userFile.setHasHHSuffix("hh".equals(file.getFileExtension()));
		userFile.setSourceName(null);
		userFile.setHasCCSuffix(false);
		userFiles.add(userFile);

		addAdditionallyIncludedDirectory(file.getLocation().removeLastSegments(1));
	}

	/**
	 * Adds a file to the list of C/C++ source files.
	 * <p/>
	 * The directory of the file must always point to a working directory,
	 * or a central storage directory.
	 *
	 * @param file      the file to be added.
	 * @param directory in which this file can be found (null if the working
	 *                  directory of the actual project)
	 */
	public void addUserSourceFile(final IFile file, final String directory) {
		final String name = file.getName();
		final String filePrefix = name.substring(0, name.lastIndexOf('.'));

		for (final UserStruct other : userFiles) {
			if (other.getDirectory() != null && other.getFilePrefix() != null && other.getDirectory().equals(directory)
					&& other.getFilePrefix().equals(filePrefix)) {
				other.setSourceName(name);
				if (file.getLocation() == null) {
					other.setOriginalSourceLocation(directory + File.separatorChar + file.getName());
				} else {
					other.setOriginalSourceLocation(file.getLocation().toOSString());
				}
				other.setHasCCSuffix("cc".equals(file.getFileExtension()));
				return;
			}
		}

		final UserStruct userFile = new UserStruct();
		userFile.setDirectory(directory);
		if (file.getLocation() == null) {
			userFile.setOriginalSourceLocation(directory + File.separatorChar + file.getName());
		} else {
			userFile.setOriginalSourceLocation(file.getLocation().toOSString());
		}

		userFile.setFileName(name);
		userFile.setFilePrefix(name.substring(0, name.lastIndexOf('.')));
		userFile.setHeaderName(null);
		userFile.setHasHHSuffix(false);
		userFile.setSourceName(file.getName());
		userFile.setHasCCSuffix("cc".equals(file.getFileExtension()));
		userFiles.add(userFile);

		addAdditionallyIncludedDirectory(file.getLocation().removeLastSegments(1));
	}

	/**
	 * Adds a file to the list of other files.
	 * <p/>
	 * The directory of the file must always point to a working directory,
	 * or a central storage directory.
	 *
	 * @param file      the file to be added.
	 * @param directory in which this file can be found (null if the working
	 *                  directory of the actual project)
	 */
	public void addOtherFiles(final IFile file, final String directory) {

		String originalLocation;
		if (file.getLocation() == null) {
			originalLocation = directory + File.separatorChar + file.getName();
		} else {
			originalLocation = file.getLocation().toOSString();
		}
		if ("null".equals(originalLocation)) {
			originalLocation = null;
		}

		final OtherFileStruct otherFile = new OtherFileStruct(directory, originalLocation, file.getName());
		otherFiles.add(otherFile);
	}

	/**
	 * Adds a file to the list of .ttcnpp files.
	 * <p/>
	 * The directory of the file must always point to a working directory,
	 * or a central storage directory.
	 *
	 * @param file      the file to be added.
	 * @param directory in which this file can be found (null if the working
	 *                  directory of the actual project)
	 */
	public void addPreprocessingModule(final IFile file, final String directory) {
		preprocess = true;
		final String name = file.getName();
		String originalLocation;
		if (file.getLocation() == null) {
			originalLocation = directory + File.separatorChar + file.getName();
		} else {
			originalLocation = file.getLocation().toOSString();
		}

		final ModuleStruct module = new ModuleStruct(directory, originalLocation, name, name.substring(0, name.lastIndexOf('.')));
		module.setRegular(false);

		ttcnppModules.add(module);
	}

	/**
	 * Adds a file to the list of .ttcnin files.
	 * <p/>
	 * The directory of the file must always point to a working directory,
	 * or a central storage directory.
	 *
	 * @param file      the file to be added.
	 * @param directory in which this file can be found (null if the working
	 *                  directory of the actual project)
	 */
	public void addIncludeModule(final IFile file, final String directory) {
		preprocess = true;

		String originalLocation;
		if (file.getLocation() == null) {
			originalLocation = directory + File.separatorChar + file.getName();
		} else {
			originalLocation = file.getLocation().toOSString();
		}
		if ("null".equals(originalLocation)) {
			originalLocation = null;
		}

		final IProject project = file.getProject();
		final IPath parentPath = file.getLocation().removeLastSegments(1);
		TTCN3IncludeFileStruct includeFile = null;
		if (usingSymbolicLinks) {
			final IPath workingDirectoryPath = ProjectBasedBuilder.getProjectBasedBuilder(project).getWorkingDirectoryPath(true);
			final String workspaceFilePath = workingDirectoryPath.toOSString() + File.separatorChar + file.getName();

			includeFile = new TTCN3IncludeFileStruct(parentPath.toOSString(),workingDirectoryPath.toOSString(),
							originalLocation, workspaceFilePath, file.getName());
			addAdditionallyIncludedDirectory(workingDirectoryPath);
		} else {
			includeFile = new TTCN3IncludeFileStruct(parentPath.toOSString(), null,
							originalLocation, null, file.getName());
			addAdditionallyIncludedDirectory(parentPath);
		}
		ttcn3IncludeFiles.add(includeFile);
	}

	/**
	 * Adds a folder to the list of folders to be added as search locations
	 * if the build is to be done without symbolic links..
	 * <p/>
	 * An include folder must always point to a directory.
	 *
	 * @param folder the folder to be added.
	 */
	public void addAdditionallyIncludedDirectory(final IPath folder) {
		if (folder == null) {
			return;
		}

		final String name = folder.toOSString();

		for (final BaseDirectoryStruct dir : additionallyIncludedFolders) {
			if (dir.getDirectoryName().equals(name)) {
				if (dir.getDirectory() == null) {
					dir.setDirectory(folder);
				}
				return;
			}
		}

		final BaseDirectoryStruct dir = new BaseDirectoryStruct(folder, name, true);

		additionallyIncludedFolders.add(dir);
	}

	/**
	 * Adds a folder to the list of base folders.
	 * <p/>
	 * A base folder must always point to a working directory, or a central
	 * storage directory.
	 *
	 * @param folder the folder to be added.
	 */
	public void addBaseDirectory(final IPath folder) {
		if (folder == null) {
			return;
		}

		final String name = folder.toOSString();

		for (final BaseDirectoryStruct dir : baseDirectories) {
			if (dir.getDirectoryName().equals(name)) {
				if (dir.getDirectory() == null) {
					dir.setDirectory(folder);
				}
				return;
			}
		}

		final BaseDirectoryStruct dir = new BaseDirectoryStruct(folder, name, true);

		baseDirectories.add(dir);
	}

	/**
	 * Adds a folder to the list of base folders.
	 * <p/>
	 * A base folder must always point to a working directory, or a central
	 * storage directory.
	 *
	 * @param folder the folder to be added.
	 */
	public void addBaseDirectory(final String folder) {
		for (final BaseDirectoryStruct dir : baseDirectories) {
			if (dir.getDirectoryName().equals(folder)) {
				return;
			}
		}

		final BaseDirectoryStruct dir = new BaseDirectoryStruct(null, folder, true);

		baseDirectories.add(dir);
	}

	public List<BaseDirectoryStruct> getBaseDirectories() {
		return baseDirectories;
	}

	public IProject getProject() {
		return project;
	}

	public boolean isAllProjectsUseSymbolicLinks() {
		return allProjectsUseSymbolicLinks;
	}

	public void setAllProjectsUseSymbolicLinks(final boolean allProjectsUseSymbolicLinks) {
		this.allProjectsUseSymbolicLinks = allProjectsUseSymbolicLinks;
	}

	public String getProjectLocation() {
		return projectLocation;
	}

	public List<ModuleStruct> getTtcn3Modules() {
		return ttcn3Modules;
	}

	public List<ModuleStruct> getTtcnppModules() {
		return ttcnppModules;
	}

	public List<TTCN3IncludeFileStruct> getTtcn3IncludeFiles() {
		return ttcn3IncludeFiles;
	}

	public List<ModuleStruct> getAsn1modules() {
		return asn1modules;
	}

	public List<UserStruct> getUserFiles() {
		return userFiles;
	}

	public List<OtherFileStruct> getOtherFiles() {
		return otherFiles;
	}

	public List<BaseDirectoryStruct> getAdditionallyIncludedFolders() {
		return additionallyIncludedFolders;
	}

	public List<IProject> getReachableProjects() {
		return reachableProjects;
	}
}