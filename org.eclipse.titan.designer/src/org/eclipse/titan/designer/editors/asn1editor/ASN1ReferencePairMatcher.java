/******************************************************************************
 * Copyright (c) 2000-2019 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************/
package org.eclipse.titan.designer.editors.asn1editor;

import org.eclipse.titan.designer.editors.GeneralPairMatcher;
import org.eclipse.titan.designer.editors.Pair;

public class ASN1ReferencePairMatcher extends GeneralPairMatcher {
	public ASN1ReferencePairMatcher() {
		this.pairs = new Pair[] { new Pair('(', ')'), new Pair('[', ']') };
	}

	@Override
	protected String getPartitioning() {
		return PartitionScanner.ASN1_PARTITIONING;
	}
}
