/******************************************************************************
 * Copyright (c) 2000-2021 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************/
package org.eclipse.titan.designer.core;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.titan.common.logging.ErrorReporter;
import org.eclipse.titan.designer.Activator;
import org.eclipse.titan.designer.GeneralConstants;
import org.eclipse.titan.designer.AST.Module;
import org.eclipse.titan.designer.compiler.BuildTimestamp;
import org.eclipse.titan.designer.compiler.ProjectSourceCompiler;
import org.eclipse.titan.designer.consoles.TITANDebugConsole;
import org.eclipse.titan.designer.license.LicenseValidator;
import org.eclipse.titan.designer.parsers.GlobalParser;
import org.eclipse.titan.designer.parsers.ProjectSourceParser;
import org.eclipse.titan.designer.preferences.PreferenceConstants;
import org.eclipse.titan.designer.productUtilities.ProductConstants;

/**
 * Build system for java code generation.
 * @author Kristof Szabados
 * @author Arpad Lovassy
 */
public class TITANJavaBuilder extends IncrementalProjectBuilder {
	public static final String BUILDER_ID = ProductConstants.PRODUCT_ID_DESIGNER + ".core.TITANJavaBuilder";

	@Override
	protected IProject[] build(final int kind, final Map<String, String> args, final IProgressMonitor monitor) throws CoreException {
		final IProject project = getProject();

		if (!LicenseValidator.check()) {
			return project.getReferencedProjects();
		}

		final IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		final boolean reportDebugInformation = store.getBoolean(PreferenceConstants.DISPLAYDEBUGINFORMATION);

		final SubMonitor progress = SubMonitor.convert(monitor);
		progress.beginTask("Build", 2);

		final ProjectSourceParser sourceParser = GlobalParser.getProjectSourceParser(project);
		//TODO: this is temporary code!
		sourceParser.makefileCreatingAnalyzeAll();

		progress.worked(1);

		final BuildTimestamp timestamp = BuildTimestamp.getNewBuildCounter();
		final IProgressMonitor codeGeneratorMonitor = progress.newChild(1);
		final Collection<Module> localModules = sourceParser.getModules();
		final Set<String> knownModuleNames = sourceParser.getKnownModuleNames();
		codeGeneratorMonitor.beginTask("Checking prerequisites", localModules.size() + 1);
		int generatedCount = 0;
		for(final Module module : localModules) {
			if (codeGeneratorMonitor.isCanceled()) {
				break;
			}

			//TODO enable the forcing of re-generation of all modules
			if ((kind == INCREMENTAL_BUILD || kind == AUTO_BUILD) && !module.shouldBeGenerated()) {
				continue;
			}

			TITANDebugConsole.println("Generating code for module `" + module.getIdentifier().getDisplayName() + "'");
			try {
				ProjectSourceCompiler.compile(timestamp, module, reportDebugInformation );
				if (ProjectSourceCompiler.generated) {
					generatedCount++;
				}
			} catch ( Exception e ) {
				ErrorReporter.logExceptionStackTrace("While generating Java code for module " + module.getIdentifier().getDisplayName(), e);
			}

			codeGeneratorMonitor.worked(1);
		}
		TITANDebugConsole.println("Generated " + generatedCount + " Java files.");
		TITANDebugConsole.println("Generating code for single main");
		try {
			ProjectSourceCompiler.generateGeneratedPackageInfo(project);
			ProjectSourceCompiler.generateSingleMain( project, knownModuleNames);
			ProjectSourceCompiler.generateParallelMain(project, knownModuleNames);
		} catch ( CoreException e ) {
			ErrorReporter.logExceptionStackTrace("While generating Java code for main module ", e);
		}

		codeGeneratorMonitor.worked(1);
		codeGeneratorMonitor.done();

		return new IProject[0];
	}

	@Override
	protected void clean(final IProgressMonitor monitor) throws CoreException {
		//TODO This is a temporary solution
		super.clean(monitor);

		final SubMonitor progress = SubMonitor.convert(monitor, 100);
		progress.subTask("Deleting " + GeneralConstants.JAVA_SOURCE_DIR + "/org");

		final IProject project = getProject();
		IFolder folder = project.getFolder(GeneralConstants.JAVA_SOURCE_DIR + "/org");
		if( folder.exists() ) {
			try {
				folder.delete(true, progress.newChild(40));
			} catch (CoreException e) {
				ErrorReporter.logExceptionStackTrace("While cleaning generated code in " + GeneralConstants.JAVA_SOURCE_DIR, e);
			}
		}

		progress.subTask("Deleting " + GeneralConstants.JAVA_BUILD_DIR);
		folder = project.getFolder(GeneralConstants.JAVA_BUILD_DIR);
		if( folder.exists() ) {
			try {
				folder.delete(true, progress.newChild(50));
			} catch (CoreException e) {
				ErrorReporter.logExceptionStackTrace("While cleaning generated code in " + GeneralConstants.JAVA_BUILD_DIR, e);
			}
		}
		
		progress.subTask("Deleting " + GeneralConstants.JAVA_TEMP_DIR);
		folder = project.getFolder(GeneralConstants.JAVA_TEMP_DIR);
		if( folder.exists() ) {
			try {
				folder.delete(true, progress.newChild(10));
			} catch (CoreException e) {
				ErrorReporter.logExceptionStackTrace("While cleaning files in " + GeneralConstants.JAVA_TEMP_DIR, e);
			}
		}

		try {
			ProjectSourceCompiler.generateGeneratedPackageInfo(project);
		} catch ( CoreException e ) {
			ErrorReporter.logExceptionStackTrace("While generating Java package_info ", e);
		}

		progress.done();
	}

	/**
	 * This function checks the project for the TITANJavaBuilder.
	 * 
	 * @param project
	 *                the project we would like to build
	 * @return whether the project has the TITANJavaBuilder enabled on it, or
	 *         not.
	 */
	public static boolean isBuilderEnabled(final IProject project) {
		if (!project.isAccessible()) {
			return false;
		}

		IProjectDescription description;
		try {
			description = project.getDescription();
		} catch (CoreException e) {
			return false;
		}

		final ICommand[] cmds = description.getBuildSpec();
		for (int i = 0; i < cmds.length; i++) {
			if (BUILDER_ID.equals(cmds[i].getBuilderName())) {
				return true;
			}
		}

		return false;
	}
}
