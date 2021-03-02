/******************************************************************************
 * Copyright (c) 2000-2021 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************/
package org.eclipse.titan.designer;

import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.titan.common.product.ProductIdentity;
import org.eclipse.titan.designer.productUtilities.ProductConstants;
import org.eclipse.titan.designer.properties.data.FileBuildPropertyData;
import org.eclipse.titan.designer.properties.data.FolderBuildPropertyData;

/**
 * Constants that did not fit into any other group.
 * 
 * @author Kristof Szabados
 * @author Arpad Lovassy
 */
public final class GeneralConstants {
	public static final ProductIdentity ON_THE_FLY_ANALYZER_VERSION =
			ProductIdentity.getProductIdentity(ProductIdentity.TITAN_PRODUCT_NUMBER, 
					ProductIdentity.TITAN_VERSION_MAJOR, ProductIdentity.TITAN_VERSION_MINOR, 
					ProductIdentity.TITAN_VERSION_PATCHLEVEL, 0);
	public static final boolean DEBUG = true;
	public static final boolean ETSI_BUILD = false;

	public static final String COMPILER_ERRORMARKER = ProductConstants.PRODUCT_ID_DESIGNER + ".compilerErrorMarker";
	public static final String COMPILER_WARNINGMARKER = ProductConstants.PRODUCT_ID_DESIGNER + ".compilerWarningMarker";
	public static final String COMPILER_INFOMARKER = ProductConstants.PRODUCT_ID_DESIGNER + ".compilerInfoMarker";
	public static final String ONTHEFLY_SYNTACTIC_MARKER = ProductConstants.PRODUCT_ID_DESIGNER + ".ontheflySyntacticMarker";
	public static final String ONTHEFLY_SEMANTIC_MARKER = ProductConstants.PRODUCT_ID_DESIGNER + ".ontheflySemanticMarker";
	public static final String ONTHEFLY_TASK_MARKER = ProductConstants.PRODUCT_ID_DESIGNER + ".ontheflyTaskMarker";
	// Places syntactic markers in semantic time...
	public static final String ONTHEFLY_MIXED_MARKER = ProductConstants.PRODUCT_ID_DESIGNER + ".ontheflyMixedMarker"; 

	// for the Combo settings of the syntactic and semantic check options.
	public static final String IGNORE = "ignore";
	public static final String WARNING = "warning";
	public static final String ERROR = "error";

	// naming rules
	public static final String OLD = "old";
	public static final String NEW = "new";
	public static final String UNSPECIFIED = "unspecified";

	// code splitting
	public static final String NONE = "none";
	public static final String TYPE = "type";
	public static final String NUMBER = "number";
	public static final String NUMBER_DEFAULT = "1";

	public static final String VERSION_STRING = ON_THE_FLY_ANALYZER_VERSION.toString();

	public static final String PROJECT_PROPERTY_PAGE = "org.eclipse.titan.designer.properties.pages.ProjectBuildPropertyPage";

	public static final QualifiedName PROJECT_UP_TO_DATE = new QualifiedName("org.eclipse.titan.designer.GeneralConstants", "upToDate");

	// TODO maybe these shouldn't have been extracted here
	public static final QualifiedName EXCLUDED_FILE_QUALIFIER = new QualifiedName(FileBuildPropertyData.QUALIFIER,
			FileBuildPropertyData.EXCLUDE_FROM_BUILD_PROPERTY);
	public static final QualifiedName EXCLUDED_FOLDER_QUALIFIER = new QualifiedName(FolderBuildPropertyData.QUALIFIER,
			FolderBuildPropertyData.EXCLUDE_FROM_BUILD_PROPERTY);

	public static final String ACTIVITY_DEBUG = "org.eclipse.titan.designer.activities.debug";
	
	public static final String JAVA_BUILD_DIR = "java_bin";
	public static final String JAVA_SOURCE_DIR = "java_src";
	public static final String JAVA_TEMP_DIR = "temp";
	public static final String JAVA_USER_PROVIDED = "user_provided";

	/** private constructor to disable instantiation */
	private GeneralConstants() {
	}
}
