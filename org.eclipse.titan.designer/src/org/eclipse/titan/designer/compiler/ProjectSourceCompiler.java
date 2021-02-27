/******************************************************************************
 * Copyright (c) 2000-2021 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************/
package org.eclipse.titan.designer.compiler;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.titan.common.utils.CommentUtils;
import org.eclipse.titan.common.utils.FileUtils;
import org.eclipse.titan.designer.GeneralConstants;
import org.eclipse.titan.designer.AST.MarkerHandler;
import org.eclipse.titan.designer.AST.Module;
import org.eclipse.titan.designer.consoles.TITANDebugConsole;
import org.eclipse.titan.designer.parsers.GlobalParser;
import org.eclipse.titan.designer.parsers.ProjectSourceParser;

/**
 * This is project level root of all java compiler related activities.
 * @author Arpad Lovassy
 * @author Adam Knapp
 */
public final class ProjectSourceCompiler {
	private final static String PACKAGE_RUNTIME_ROOT = "org.eclipse.titan.runtime.core";
	public static boolean generated;

	/**
	 * Private constructor to disable accidental instantiation.
	 * */
	private ProjectSourceCompiler() {
		//intentionally empty
	}

	/**
	 * Returns the string {@code org.eclipse.titan.runtime.core}
	 * @return Returns the string {@code org.eclipse.titan.runtime.core}
	 */
	public static String getPackageRuntimeRoot() {
		return PACKAGE_RUNTIME_ROOT;
	}

	/** 
	 * Returns the root package of the generated java source
	 * @param project Project to which the package is related to
	 * @return The root package of the generated java source
	 */
	public static String getPackageGeneratedRoot(final IProject project) {
		final String projectName = project.getName().replaceAll("[^\\p{IsAlphabetic}^\\p{IsDigit}]", "_");

		return MessageFormat.format("org.eclipse.titan.{0}.generated", projectName);
	}

	/**
	 * Returns the root package of the user provided java source
	 * @param project Project to which the package is related to
	 * @return The root package of the user provided java source
	 */
	public static String getPackageUserProvidedRoot(final IProject project) {
		final String projectName = project.getName().replaceAll("[^\\p{IsAlphabetic}^\\p{IsDigit}]", "_");

		return MessageFormat.format("org.eclipse.titan.{0}.user_provided", projectName);
	}

	/** 
	 * Returns the root folder (path) of the generated java source 
	 * @param project Project to which the folder is related to
	 * @return The root folder (path) of the generated java source
	 */
	public static String getGeneratedRoot(final IProject project) {
		final String projectName = project.getName().replaceAll("[^\\p{IsAlphabetic}^\\p{IsDigit}]", "_");

		return MessageFormat.format("java_src/org/eclipse/titan/{0}/generated", projectName);
	}

	/**
	 * Returns the root folder (path) of the user provided java source
	 * @param project Project to which the folder is related to
	 * @return The root folder (path) of the user provided java source
	 */
	public static String getUserProvidedRoot(final IProject project) {
		final String projectName = project.getName().replaceAll("[^\\p{IsAlphabetic}^\\p{IsDigit}]", "_");

		return MessageFormat.format("user_provided/org/eclipse/titan/{0}/user_provided", projectName);
	}

	public static void generateSourceFolder(final IProject project) throws CoreException {
		final IFolder folder = project.getFolder( getGeneratedRoot(project) );
		FileUtils.createDir( folder );
	}

	/**
	 * Generates java code for a module
	 * @param timestamp the timestamp of this build
	 * @param aModule module to compile
	 * @param aDebug true: debug info is added to the source code
	 * @throws CoreException
	 */
	public static void compile(final BuildTimestamp timestamp, final Module aModule, final boolean aDebug ) throws CoreException {
		generated = false;
		final IResource sourceFile = aModule.getLocation().getFile();
		if(MarkerHandler.hasMarker(GeneralConstants.ONTHEFLY_SYNTACTIC_MARKER, sourceFile, IMarker.SEVERITY_ERROR)
				|| MarkerHandler.hasMarker(GeneralConstants.ONTHEFLY_MIXED_MARKER, sourceFile)
				|| MarkerHandler.hasMarker(GeneralConstants.ONTHEFLY_SEMANTIC_MARKER, sourceFile, IMarker.SEVERITY_ERROR)) {
			// if there are syntactic errors in the module don't generate code for it
			// TODO semantic errors need to be checked for severity
			return;
		}

		final JavaGenData data = new JavaGenData(aModule, timestamp);
		data.collectProjectSettings(aModule.getLocation());
		data.setDebug( aDebug );
		aModule.generateCode( data );

		if (data.getAddSourceInfo() && (data.getPreInit().length() > 0 || data.getPostInit().length() > 0)) {
			data.addCommonLibraryImport("TTCN_Logger.TTCN_Location");
			data.addCommonLibraryImport("TTCN_Logger.TTCN_Location.entity_type_t");
		}
		if (data.getStartPTCFunction().length() > 0) {
			data.addBuiltinTypeImport("Text_Buf");
		}

		final IProject project = aModule.getProject();
		final StringBuilder sourceCode = new StringBuilder();
		//write imports
		//final StringBuilder headerSb = new StringBuilder();
		writeHeader( project, sourceCode, data );
		//sourceCode.append(headerSb);

		sourceCode.append(data.getGlobalVariables());
		sourceCode.append( '\n' );

		sourceCode.append(data.getConstructor());
		for(final StringBuilder typeString: data.getTypes().values()) {
			sourceCode.append(typeString);
		}

		for(final String typeConversion: data.getTypesConversions().values()) {
			sourceCode.append(typeConversion);
		}

		sourceCode.append(data.getSrc());

		writeFooter(data, sourceCode, sourceFile, aModule);


		//write src file body
		
		final IFolder folder = project.getFolder( getGeneratedRoot(project) );
		final IFile file = folder.getFile( aModule.getName() + ".java");
		FileUtils.createDir( folder );

		//write to file if changed
		final String content = sourceCode.toString();
		if (file.exists()) {
			if(needsUpdate(file, content) ) {
				final InputStream outputStream = new ByteArrayInputStream( content.getBytes() );
				file.setContents( outputStream, IResource.FORCE | IResource.KEEP_HISTORY, null );
				generated = true;
				TITANDebugConsole.println("re-Generated code for module `" + aModule.getIdentifier().getDisplayName() + "'");
			}
		} else {
			final InputStream outputStream = new ByteArrayInputStream( content.getBytes() );
			file.create( outputStream, IResource.FORCE, null );
			generated = true;
		}
	}

	/**
	 * Generates the common header comments: what generated the file, copyright
	 * <pre>
	 * // This Java file was generated by the TITAN Designer eclipse plug-in
	 * // of the TTCN-3 Test Executor version {version}
	 * // for ({username}@{hostname})
 	 * // Copyright (c) 2000-2021 Ericsson Telecom AB
 	 * </pre>
 	 * 
 	 * @param contentBuilder string buffer, where the result is written
 	 * 
	 */
	public static void generateCommonHeaderComments(StringBuilder contentBuilder) {
		if (contentBuilder == null)
			return;
		contentBuilder.append(CommentUtils.getHeaderCommentsWithCopyright("// ", GeneralConstants.VERSION_STRING));
	}

	/**
	 * Generates the package-info.java file in the generated package.
	 *
	 * @param project the project in which the code is generated.
	 *
	 * @throws CoreException if file operations can not be performed.
	 * */
	public static void generateGeneratedPackageInfo(final IProject project) throws CoreException {
		final IFolder folder = project.getFolder( getGeneratedRoot(project) );
		final IFile file = folder.getFile("package-info.java");
		FileUtils.createDir( folder );

		final StringBuilder contentBuilder = new StringBuilder();
		
		generateCommonHeaderComments(contentBuilder);

		contentBuilder.append("/**\n");
		contentBuilder.append(MessageFormat.format(" * <code>{0}</code> package contains classes\n", getPackageGeneratedRoot(project)));
		contentBuilder.append(MessageFormat.format(" * that were generated by Titan's Java code generator, from the TTCN-3 and ASN.1 source codes in the project {0}.\n", project.getName()));
		contentBuilder.append(" *<p>\n");
		contentBuilder.append(" * ").append(CommentUtils.DO_NOT_EDIT_TEXT).append("\n");
		contentBuilder.append(" */\n");
		contentBuilder.append( "package " );
		contentBuilder.append( getPackageGeneratedRoot(project) );
		contentBuilder.append( ";\n\n" );

		final String content = contentBuilder.toString();
		if (file.exists()) {
			if(needsUpdate(file, content.toString()) ) {
				final InputStream outputStream = new ByteArrayInputStream( content.getBytes() );
				file.setContents( outputStream, IResource.FORCE | IResource.KEEP_HISTORY, null );
			}
		} else {
			final InputStream outputStream = new ByteArrayInputStream( content.getBytes() );
			file.create( outputStream, IResource.FORCE, null );
		}
	}

	/**
	 * Generates the package-info.java file in the user_provided package.
	 *
	 * @param project the project in which the code is generated.
	 *
	 * @throws CoreException if file operations can not be performed.
	 * */
	public static void generateUserProvidedPackageInfo(final IProject project) throws CoreException {
		final IFolder folder = project.getFolder( getUserProvidedRoot(project) );
		final IFile file = folder.getFile("package-info.java");
		FileUtils.createDir( folder );

		final StringBuilder contentBuilder = new StringBuilder();

		generateCommonHeaderComments(contentBuilder);
		
		contentBuilder.append("/**\n");
		contentBuilder.append(MessageFormat.format(" * <code>{0}</code> package contains classes\n", getPackageUserProvidedRoot(project)));
		contentBuilder.append(MessageFormat.format(" * that were written by the user, and contain the implementations of test ports and external functions, in the project {0}.\n", project.getName()));
		contentBuilder.append(" *<p>\n");
		contentBuilder.append(" * ").append(CommentUtils.DO_NOT_EDIT_TEXT).append("\n");
		contentBuilder.append(" */\n");
		contentBuilder.append( "package " );
		contentBuilder.append( getPackageUserProvidedRoot(project) );
		contentBuilder.append( ";\n\n" );

		final String content = contentBuilder.toString();
		if (file.exists()) {
			if(needsUpdate(file, content.toString()) ) {
				final InputStream outputStream = new ByteArrayInputStream( content.getBytes() );
				file.setContents( outputStream, IResource.FORCE | IResource.KEEP_HISTORY, null );
			}
		} else {
			final InputStream outputStream = new ByteArrayInputStream( content.getBytes() );
			file.create( outputStream, IResource.FORCE, null );
		}
	}

	/**
	 * Generates the class that will be the entry point for single mode execution.
	 *
	 * @param project the project in which the code is generated.
	 * @param modules the list of modules generated during this build.
	 *
	 * @throws CoreException if file operations can not be performed.
	 * */
	public static void generateSingleMain(final IProject project, final Set<String> knownModuleNames) throws CoreException {
		final IFolder folder = project.getFolder( getGeneratedRoot(project) );
		final IFile file = folder.getFile("Single_main.java");
		FileUtils.createDir( folder );

		final StringBuilder contentBuilder = new StringBuilder();
		
		generateCommonHeaderComments(contentBuilder);
		
		contentBuilder.append( "// "+ CommentUtils.DO_NOT_EDIT_TEXT + "\n" );
		contentBuilder.append( '\n' );
		contentBuilder.append( "package " );
		contentBuilder.append( getPackageGeneratedRoot(project) );
		contentBuilder.append( ";\n\n" );

		contentBuilder.append(MessageFormat.format("import {0}.Module_List;\n", PACKAGE_RUNTIME_ROOT));
		contentBuilder.append(MessageFormat.format("import {0}.PreGenRecordOf;\n", PACKAGE_RUNTIME_ROOT));
		contentBuilder.append(MessageFormat.format("import {0}.Runtime_Single_main;\n", PACKAGE_RUNTIME_ROOT));
		contentBuilder.append(MessageFormat.format("import {0}.TTCN_Logger;\n", PACKAGE_RUNTIME_ROOT));
		contentBuilder.append(MessageFormat.format("import {0}.TitanLoggerApi;\n", PACKAGE_RUNTIME_ROOT));

		final ProjectSourceParser sourceParser = GlobalParser.getProjectSourceParser(project);
		for ( final String moduleName : knownModuleNames ) {
			final Module referencedModule = sourceParser.getModuleByName(moduleName);
			contentBuilder.append(MessageFormat.format("import {0}.{1};\n", getPackageGeneratedRoot(referencedModule.getProject()), moduleName));
		}

		contentBuilder.append("\npublic class Single_main {\n\n");
		contentBuilder.append("\tpublic static void main( String[] args ) {\n");
		contentBuilder.append("\t\tlong absoluteStart = System.nanoTime();\n");
		contentBuilder.append("\t\tModule_List.add_module(new PreGenRecordOf());\n");
		contentBuilder.append("\t\tModule_List.add_module(new TitanLoggerApi());\n");
		for ( final String moduleName : knownModuleNames ) {
			contentBuilder.append(MessageFormat.format("\t\tModule_List.add_module(new {0}());\n", moduleName));
		}
		contentBuilder.append(MessageFormat.format("\t\tTTCN_Logger.set_executable_name(\"{0}\");\n", project.getName()));
		contentBuilder.append("\t\tint returnValue = Runtime_Single_main.singleMain( args );\n");
		contentBuilder.append("\t\tSystem.out.println(\"Total execution took \" + (System.nanoTime() - absoluteStart) * (1e-9) + \" seconds to complete\");\n");
		contentBuilder.append("\t\tSystem.exit(returnValue);\n");
		contentBuilder.append("\t}\n");
		contentBuilder.append("}\n\n");

		final String content = contentBuilder.toString();
		if (file.exists()) {
			if(needsUpdate(file, content.toString()) ) {
				final InputStream outputStream = new ByteArrayInputStream( content.getBytes() );
				file.setContents( outputStream, IResource.FORCE | IResource.KEEP_HISTORY, null );
			}
		} else {
			final InputStream outputStream = new ByteArrayInputStream( content.getBytes() );
			file.create( outputStream, IResource.FORCE, null );
		}
	}

	/**
	 * Generates the class that will be the entry point for parallel mode execution.
	 *
	 * @param project the project in which the code is generated.
	 * @param modules the list of modules generated during this build.
	 *
	 * @throws CoreException if file operations can not be performed.
	 * */
	public static void generateParallelMain(final IProject project, final Set<String> knownModuleNames) throws CoreException {
		final IFolder folder = project.getFolder( getGeneratedRoot(project) );
		final IFile file = folder.getFile("Parallel_main.java");
		FileUtils.createDir( folder );

		final StringBuilder contentBuilder = new StringBuilder();

		generateCommonHeaderComments(contentBuilder);
		
		contentBuilder.append( "// " + CommentUtils.DO_NOT_EDIT_TEXT + "\n" );
		contentBuilder.append( '\n' );
		contentBuilder.append( "package " );
		contentBuilder.append( getPackageGeneratedRoot(project) );
		contentBuilder.append( ";\n\n" );

		contentBuilder.append(MessageFormat.format("import {0}.Module_List;\n", PACKAGE_RUNTIME_ROOT));
		contentBuilder.append(MessageFormat.format("import {0}.PreGenRecordOf;\n", PACKAGE_RUNTIME_ROOT));
		contentBuilder.append(MessageFormat.format("import {0}.Runtime_Parallel_main;\n", PACKAGE_RUNTIME_ROOT));
		contentBuilder.append(MessageFormat.format("import {0}.TTCN_Logger;\n", PACKAGE_RUNTIME_ROOT));
		contentBuilder.append(MessageFormat.format("import {0}.TitanLoggerApi;\n", PACKAGE_RUNTIME_ROOT));

		final ProjectSourceParser sourceParser = GlobalParser.getProjectSourceParser(project);
		for ( final String moduleName : knownModuleNames ) {
			final Module referencedModule = sourceParser.getModuleByName(moduleName);
			contentBuilder.append(MessageFormat.format("import {0}.{1};\n", getPackageGeneratedRoot(referencedModule.getProject()), moduleName));
		}

		contentBuilder.append("\npublic class Parallel_main {\n\n");
		contentBuilder.append("\tpublic static void main( String[] args ) {\n");
		contentBuilder.append("\t\tlong absoluteStart = System.nanoTime();\n");
		contentBuilder.append("\t\tModule_List.add_module(new PreGenRecordOf());\n");
		contentBuilder.append("\t\tModule_List.add_module(new TitanLoggerApi());\n");
		for ( final String moduleName : knownModuleNames ) {
			contentBuilder.append(MessageFormat.format("\t\tModule_List.add_module(new {0}());\n", moduleName));
		}
		contentBuilder.append(MessageFormat.format("\t\tTTCN_Logger.set_executable_name(\"{0}\");\n", project.getName()));
		contentBuilder.append("\t\tint returnValue = Runtime_Parallel_main.parallelMain(args);\n");
		contentBuilder.append("\t\tSystem.out.println(\"Total execution took \" + (System.nanoTime() - absoluteStart) * (1e-9) + \" seconds to complete\");\n");
		contentBuilder.append("\t\tSystem.exit(returnValue);\n");
		contentBuilder.append("\t}\n" );
		contentBuilder.append("}\n\n" );

		final String content = contentBuilder.toString();
		if (file.exists()) {
			if(needsUpdate(file, content.toString()) ) {
				final InputStream outputStream = new ByteArrayInputStream( content.getBytes() );
				file.setContents( outputStream, IResource.FORCE | IResource.KEEP_HISTORY, null );
			}
		} else {
			final InputStream outputStream = new ByteArrayInputStream( content.getBytes() );
			file.create( outputStream, IResource.FORCE, null );
		}
	}

	/**
	 * Compares the content of the file and the provided string content,
	 *  to determine if the file content needs to be updated or not.
	 *
	 * @param file the file to check
	 * @param content the string to be generated if not already present in the file
	 * @return true if the file does not contain the provided string parameter
	 * */
	private static boolean needsUpdate(final IFile file, final String content) throws CoreException {
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

	/**
	 * Builds header part of the java source file.
	 * <ul>
	 *   <li> header comment
	 *   <li> package
	 *   <li> includes
	 * </ul>
	 * @param aSb string buffer, where the result is written
	 * @param aData data collected during code generation, we need the include files form it
	 */
	private static void writeHeader(final IProject project, final StringBuilder aSb, final JavaGenData aData ) {
		generateCommonHeaderComments(aSb);
		
		aSb.append( "// Do not edit this file unless you know what you are doing.\n" );
		aSb.append( '\n' );
		aSb.append( "package " );
		aSb.append( getPackageGeneratedRoot(project) );
		aSb.append( ";\n\n" );

		for ( final String importName : aData.getInternalImports() ) {
			aSb.append( "import " );
			aSb.append( PACKAGE_RUNTIME_ROOT );
			aSb.append( '.' );
			aSb.append( importName );
			aSb.append( ";\n" );
		}

		final ProjectSourceParser sourceParser = GlobalParser.getProjectSourceParser(project);
		for (final String importName : aData.getInterModuleImports()) {
			final Module referencedModule = sourceParser.getModuleByName(importName);
			aSb.append(MessageFormat.format("import {0}.{1};\n", getPackageGeneratedRoot(referencedModule.getProject()), importName));
		}

		for ( final String importName : aData.getImports() ) {
			writeImport( aSb, importName );
		}
		aSb.append( '\n' );

		aSb.append(aData.getClassHeader());
		aSb.append( '\n' );
	}

	/**
	 * Builds footer part of the java source file.
	 * <ul>
	 *   <li> pre init function: to initialize constants before module parameters are processed
	 *   <li> post init function: to initialize local "constants" after module parameters were processed.
	 * </ul>
	 *
	 * @param aData data collected during code generation, we need the include files form it
	 * @param sourceFile the source of the code.
	 * @param aModule module to compile
	 */
	private static void writeFooter( final JavaGenData aData, final StringBuilder aSb, final IResource sourceFile, final Module aModule) {
//		final StringBuilder aSb = aData.getSrc();
		if (aData.getSetModuleParameters().length() > 0) {
			aSb.append("\t@Override\n");
			aSb.append("\tpublic boolean set_module_param(final Param_Types.Module_Parameter param)\n");
			aSb.append("\t{\n");
			aSb.append("\t\tfinal String par_name = param.get_id().get_current_name();\n");
			aSb.append("\t\t");
			aSb.append(aData.getSetModuleParameters());
			aSb.append("{\n");
			aSb.append("\t\t\treturn false;\n");
			aSb.append("\t\t}\n");
			aSb.append("\t}\n\n");

			aSb.append("\t@Override\n");
			aSb.append("\tpublic boolean has_set_module_param() {\n");
			aSb.append("\t\treturn true;\n");
			aSb.append("\t}\n\n");
		}

		if (aData.getGetModuleParameters().length() > 0) {
			aSb.append("\t@Override\n");
			aSb.append("\tpublic Param_Types.Module_Parameter get_module_param(final Param_Types.Module_Param_Name param_name)\n");
			aSb.append("\t{\n");
			aSb.append("\t\tfinal String par_name = param_name.get_current_name();\n");
			aSb.append("\t\t");
			aSb.append(aData.getGetModuleParameters());
			aSb.append("{\n");
			aSb.append("\t\t\treturn null;\n");
			aSb.append("\t\t}\n");
			aSb.append("\t}\n\n");

			aSb.append("\t@Override\n");
			aSb.append("\tpublic boolean has_get_module_param() {\n");
			aSb.append("\t\treturn true;\n");
			aSb.append("\t}\n\n");
		}

		if (aData.getLogModuleParameters().length() > 0) {
			aSb.append("\t@Override\n");
			aSb.append("\tpublic void log_module_param()\n");
			aSb.append("\t{\n");
			aSb.append("\t\t");
			aSb.append(aData.getLogModuleParameters());
			aSb.append("\t}\n\n");
			
			aSb.append("\t@Override\n");
			aSb.append("\tpublic boolean has_log_module_param() {\n");
			aSb.append("\t\treturn true;\n");
			aSb.append("\t}\n\n");
		}

		if (aData.getPreInit().length() > 0) {
			aSb.append("\t@Override\n");
			aSb.append("\tpublic void pre_init_module()\n");
			aSb.append("\t{\n");
			aSb.append("\t\tif (pre_init_called) {\n");
			aSb.append("\t\t\treturn;\n");
			aSb.append("\t\t}\n");
			aSb.append("\t\tpre_init_called = true;\n");
			if (aData.getAddSourceInfo()) {
				aSb.append(MessageFormat.format("\t\tfinal TTCN_Location current_location = TTCN_Location.enter(\"{0}\", {1}, entity_type_t.LOCATION_UNKNOWN, \"{2}\");\n", sourceFile.getName(), 0, aModule.getIdentifier().getDisplayName()));
			}
			aSb.append(aData.getPreInit());
			if (aData.getAddSourceInfo()) {
				aSb.append("\t\tcurrent_location.leave();\n");
			}
			aSb.append("\t}\n\n");
		}

		if (aData.getPostInit().length() > 0) {
			aSb.append("\t@Override\n");
			aSb.append("\tpublic void post_init_module()\n");
			aSb.append("\t{\n");
			aSb.append("\t\tif (post_init_called) {\n");
			aSb.append("\t\t\treturn;\n");
			aSb.append("\t\t}\n");
			aSb.append("\t\tpost_init_called = true;\n");
			aSb.append("\t\tTTCN_Logger.log_module_init(module_name, false);\n");
			if (aData.getAddSourceInfo()) {
				aSb.append(MessageFormat.format("\t\tfinal TTCN_Location current_location = TTCN_Location.enter(\"{0}\", {1}, entity_type_t.LOCATION_UNKNOWN, \"{2}\");\n", sourceFile.getName(), 0, aModule.getIdentifier().getDisplayName()));
			}
			aSb.append(aData.getPostInit());
			if (aData.getAddSourceInfo()) {
				aSb.append("\t\tcurrent_location.leave();\n");
			}
			aSb.append("\t\tTTCN_Logger.log_module_init(module_name, true);\n");
			aSb.append("\t}\n\n");
		}

		if (aData.getStartPTCFunction().length() > 0) {
			aSb.append("\t@Override\n");
			aSb.append("\tpublic boolean start_ptc_function(final String function_name, final Text_Buf function_arguments) {\n");
			aSb.append("\t\t");
			aSb.append(aData.getStartPTCFunction());
			aSb.append("{\n");
			aSb.append("\t\t\tthrow new TtcnError(MessageFormat.format(\"Internal error: Startable function {0} does not exist in module {1}.\", function_name, module_name));\n");
			aSb.append("\t\t}\n");
			aSb.append("\t}\n\n");
		}

		if (aData.getExecuteTestcase().length() > 0) {
			aSb.append("\t@Override\n");
			aSb.append("\tpublic void execute_testcase(final String tescase_name) {\n");
			aSb.append("\t\t");
			aSb.append(aData.getExecuteTestcase());
			aSb.append("{\n");
			aSb.append("\t\t\tthrow new TtcnError(MessageFormat.format(\"Test case {0} does not exist in module {1}.\", tescase_name, module_name));\n");
			aSb.append("\t\t}\n");
			aSb.append("\t}\n\n");
		}

		if (aData.getExecuteAllTestcase().length() > 0) {
			aSb.append("\t@Override\n");
			aSb.append("\tpublic void execute_all_testcases() {\n");
			aSb.append(aData.getExecuteAllTestcase());
			aSb.append("\t}\n\n");
		}

		if (aData.getInitComp().length() > 0) {
			aSb.append("\tpublic boolean init_comp_type(final String component_type, final boolean init_base_comps) {\n");
			aSb.append("\t\t");
			aSb.append(aData.getInitComp());
			aSb.append("{\n");
			aSb.append("\t\t\treturn false;\n");
			aSb.append("\t\t}\n");
			aSb.append("\t}\n\n");
		}

		if (aData.getInitSystemPort().length() > 0) {
			aSb.append("\tpublic boolean init_system_port(final String component_type, final String port_name)\n");
			aSb.append("\t{\n");
			aSb.append(aData.getInitSystemPort());
			aSb.append("\t\t{\n");
			aSb.append("\t\t\treturn false;\n");
			aSb.append("\t\t}\n");
			aSb.append("\t}\n\n");
		}

		if (aData.getListTestcases().length() > 0) {
			aSb.append("\t@Override\n");
			aSb.append("\tpublic void list_testcases() {\n");
			aSb.append(aData.getListTestcases());
			aSb.append("\t}\n\n");
		}

		if (aData.getListModulePars().length() > 0) {
			aSb.append("\t@Override\n");
			aSb.append("\tpublic void list_modulepars() {\n");
			aSb.append(aData.getListModulePars());
			aSb.append("\t}\n\n");
		}

		aSb.append( "}\n" );
	}

	/**
	 * Writes an import to the header
	 * @param aSb string buffer, where the result is written
	 * @param aImportName short class name to import. This function knows the package of all the runtime classes.
	 */
	private static void writeImport( final StringBuilder aSb, final String aImportName ) {
		aSb.append( "import " );
		aSb.append( aImportName );
		aSb.append( ";\n" );
	}
}
