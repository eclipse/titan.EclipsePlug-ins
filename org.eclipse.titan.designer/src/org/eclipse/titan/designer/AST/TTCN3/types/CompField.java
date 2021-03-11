/******************************************************************************
 * Copyright (c) 2000-2021 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************/
package org.eclipse.titan.designer.AST.TTCN3.types;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.titan.designer.AST.ASTNode;
import org.eclipse.titan.designer.AST.ASTVisitor;
import org.eclipse.titan.designer.AST.GovernedSimple.CodeSectionType;
import org.eclipse.titan.designer.AST.ILocateableNode;
import org.eclipse.titan.designer.AST.INamedNode;
import org.eclipse.titan.designer.AST.IOutlineElement;
import org.eclipse.titan.designer.AST.IReferencingElement;
import org.eclipse.titan.designer.AST.IType;
import org.eclipse.titan.designer.AST.IType.TypeOwner_type;
import org.eclipse.titan.designer.AST.IType.ValueCheckingOptions;
import org.eclipse.titan.designer.AST.ITypeWithComponents;
import org.eclipse.titan.designer.AST.IValue;
import org.eclipse.titan.designer.AST.Identifier;
import org.eclipse.titan.designer.AST.Location;
import org.eclipse.titan.designer.AST.NULL_Location;
import org.eclipse.titan.designer.AST.ReferenceFinder;
import org.eclipse.titan.designer.AST.ReferenceFinder.Hit;
import org.eclipse.titan.designer.AST.Scope;
import org.eclipse.titan.designer.AST.Type;
import org.eclipse.titan.designer.AST.Value;
import org.eclipse.titan.designer.AST.TTCN3.Expected_Value_type;
import org.eclipse.titan.designer.AST.TTCN3.IAppendableSyntax;
import org.eclipse.titan.designer.AST.TTCN3.IIncrementallyUpdateable;
import org.eclipse.titan.designer.AST.TTCN3.definitions.Definition;
import org.eclipse.titan.designer.AST.TTCN3.definitions.OopVisibilityModifier;
import org.eclipse.titan.designer.declarationsearch.Declaration;
import org.eclipse.titan.designer.parsers.CompilationTimeStamp;
import org.eclipse.titan.designer.parsers.ttcn3parser.IIdentifierReparser;
import org.eclipse.titan.designer.parsers.ttcn3parser.IdentifierReparser;
import org.eclipse.titan.designer.parsers.ttcn3parser.ReParseException;
import org.eclipse.titan.designer.parsers.ttcn3parser.TTCN3ReparseUpdater;
import org.eclipse.titan.designer.parsers.ttcn3parser.Ttcn3Lexer;

/**
 * Component field.
 * <p>
 * Used to contain data about a field of a structured type.
 *
 * @author Kristof Szabados
 * @author Miklos Magyari
 * */
public final class CompField extends ASTNode
implements IOutlineElement, ILocateableNode, IAppendableSyntax, IIncrementallyUpdateable, IReferencingElement {
	private static final String FULLNAMEPART = ".<defaultValue>";

	private Identifier name;
	private final Type type;
	private final boolean optional;
	private final Value defaultValue;
	private boolean inherited;
	private final OopVisibilityModifier visibility;
	private final Location visibilityLocation;

	private Location commentLocation = null;

	/**
	 * The location of the whole component field. This location encloses the
	 * component field fully, as it is used to report errors to.
	 **/
	private Location location;

	public CompField(final Identifier name, final Type type, final boolean optional, final Value defaultValue,
			final boolean inherited, final OopVisibilityModifier visibility, Location visibilityLocation) {
		this.name = name;
		this.type = type;
		this.optional = optional;
		this.defaultValue = defaultValue;
		this.inherited = inherited;
		this.visibility = visibility;
		this.visibilityLocation = visibilityLocation;
		
		if (type != null) {
			type.setOwnertype(TypeOwner_type.OT_COMP_FIELD, this);
			type.setFullNameParent(this);
		}
		if (defaultValue != null) {
			defaultValue.setFullNameParent(this);
		}

		location = NULL_Location.INSTANCE;
	}
	
	public CompField(final Identifier name, final Type type, final boolean optional, final Value defaultValue) {
		this(name, type, optional, defaultValue, false, OopVisibilityModifier.None, null);
	}
	
	public CompField newInstance() {
		return new CompField(name, type, optional, defaultValue);
	}

	@Override
	/** {@inheritDoc} */
	public StringBuilder getFullName(final INamedNode child) {
		final StringBuilder builder = super.getFullName(child);

		builder.append(INamedNode.DOT).append(name.getDisplayName());

		if (child == defaultValue) {
			return builder.append(FULLNAMEPART);
		}
		return builder;
	}

	@Override
	/** {@inheritDoc} */
	public void setLocation(final Location location) {
		this.location = location;
	}

	@Override
	/** {@inheritDoc} */
	public Location getLocation() {
		return location;
	}

	/**
	 * @return the identifier of this component field
	 * */
	@Override
	public Identifier getIdentifier() {
		return name;
	}

	/**
	 * @return the type of this component field
	 * */
	public Type getType() {
		return type;
	}
	
	/**
	 * @return the oop visibility of this component field
	 */
	public OopVisibilityModifier getVisibility() {
		return visibility;
	}
	
	/**
	 * @return if the field is inherited from a parent
	 */
	public boolean isInherited() {
		return inherited;
	}
	
	/**
	 */
	public void setInherited(final boolean isInherited) {
		this.inherited = isInherited;
	}
	
	/**
	 * @return location of the oop visibility modifier 
	 */
	public Location getVisibilityLocation() {
		return visibilityLocation;
	}

	@Override
	/** {@inheritDoc} */
	public Object[] getOutlineChildren() {
		if (type == null) {
			return new Object[] {};
		}

		return type.getOutlineChildren();
	}

	@Override
	/** {@inheritDoc} */
	public String getOutlineText() {
		final StringBuilder text = new StringBuilder(name.getDisplayName());
		text.append(" : ");
		text.append(type.getTypename());
		return text.toString();
	}

	@Override
	/** {@inheritDoc} */
	public String getOutlineIcon() {
		if (type != null) {
			return type.getOutlineIcon();
		}

		return "titan.gif";
	}

	@Override
	/** {@inheritDoc} */
	public int category() {
		if (type == null) {
			return 0;
		}

		return type.category();
	}

	/**
	 * @return true if the component field is optional
	 * */
	public boolean isOptional() {
		return optional;
	}

	/**
	 * @return true if the component field has a default value
	 * */
	public boolean hasDefault() {
		return defaultValue != null;
	}

	/** @return the default value, or null if there is none set*/
	public Value getDefault() {
		return defaultValue;
	}

	/**
	 * @return The location of the comment assigned to this definition.
	 *  Or null if none.
	 * */
	@Override
	public Location getCommentLocation() {
		return commentLocation;
	}

	/**
	 * Sets the location of the comment that belongs to this definition.
	 *
	 * @param commentLocation the location of the comment
	 * */
	public void setCommentLocation(final Location commentLocation) {
		this.commentLocation = commentLocation;
	}

	/**
	 * Sets the actual scope of this component field.
	 *
	 * @param scope the scope to be set
	 * */
	@Override
	/** {@inheritDoc} */
	public void setMyScope(final Scope scope) {
		super.setMyScope(scope);
		if (type != null) {
			type.setMyScope(scope);
		}
		if (defaultValue != null) {
			defaultValue.setMyScope(scope);
		}
	}

	/**
	 * Does the semantic checking of this field.
	 *
	 * @param timestamp the timestamp of the actual semantic check cycle.
	 * */
	public void check(final CompilationTimeStamp timestamp) {
		if (type == null) {
			return;
		}

		type.check(timestamp);
		type.checkEmbedded(timestamp, type.getLocation(), true, "embedded into another type");

		if (defaultValue == null) {
			return;
		}

		defaultValue.setMyGovernor(type);
		final IType lastType = type.getTypeRefdLast(timestamp);
		final IValue tempValue = lastType.checkThisValueRef(timestamp, defaultValue);
		lastType.checkThisValue(timestamp, tempValue, null, new ValueCheckingOptions(Expected_Value_type.EXPECTED_CONSTANT, false, false, true, false, false));

		defaultValue.setGenNameRecursive(type.getGenNameOwn() + "_defval_");
		defaultValue.setCodeSection(CodeSectionType.CS_PRE_INIT);
	}

	@Override
	/** {@inheritDoc} */
	public List<Integer> getPossibleExtensionStarterTokens() {
		if (optional) {
			// if optional, nothing can follow
			return null;
		}

		final List<Integer> result = new ArrayList<Integer>();
		result.add(Ttcn3Lexer.OPTIONAL);

		result.add(Ttcn3Lexer.LPAREN);
		result.add(Ttcn3Lexer.LENGTH);

		// any array dimension can be added at this point
		result.add(Ttcn3Lexer.SQUAREOPEN);

		return result;
	}

	@Override
	/** {@inheritDoc} */
	public List<Integer> getPossiblePrefixTokens() {
		return new ArrayList<Integer>(0);
	}

	@Override
	/** {@inheritDoc} */
	public void updateSyntax(final TTCN3ReparseUpdater reparser, final boolean isDamaged) throws ReParseException {
		if (isDamaged) {
			boolean enveloped = false;
			if(name != null) {
				final Location tempIdentifier = name.getLocation();
				if (reparser.envelopsDamage(tempIdentifier) || reparser.isExtending(tempIdentifier)) {
					reparser.extendDamagedRegion(tempIdentifier);
					final IIdentifierReparser r = new IdentifierReparser(reparser);
					final int result = r.parse();
					name = r.getIdentifier();
					// damage handled
					if (result == 0) {
						enveloped = true;
					} else {
						throw new ReParseException(result);
					}
				}
			}

			if (type != null) {
				if (enveloped) {
					type.updateSyntax(reparser, false);
					reparser.updateLocation(type.getLocation());
				} else if (reparser.envelopsDamage(type.getLocation())) {
					type.updateSyntax(reparser, true);
					enveloped = true;
					reparser.updateLocation(type.getLocation());
				}
			}

			if (defaultValue != null) {
				if (enveloped) {
					defaultValue.updateSyntax(reparser, false);
					reparser.updateLocation(defaultValue.getLocation());
				} else if (reparser.envelopsDamage(defaultValue.getLocation())) {
					defaultValue.updateSyntax(reparser, true);
					enveloped = true;
					reparser.updateLocation(defaultValue.getLocation());
				}
			}

			if (enveloped) {
				return;
			}

			throw new ReParseException();
		}

		reparser.updateLocation(name.getLocation());
		if (type != null) {
			type.updateSyntax(reparser, false);
			reparser.updateLocation(type.getLocation());
		}
		if (defaultValue != null) {
			defaultValue.updateSyntax(reparser, false);
			reparser.updateLocation(defaultValue.getLocation());
		}
	}

	@Override
	/** {@inheritDoc} */
	public void findReferences(final ReferenceFinder referenceFinder, final List<Hit> foundIdentifiers) {
		if (type != null) {
			type.findReferences(referenceFinder, foundIdentifiers);
		}
		if (defaultValue != null) {
			defaultValue.findReferences(referenceFinder, foundIdentifiers);
		}
	}

	@Override
	/** {@inheritDoc} */
	protected boolean memberAccept(final ASTVisitor v) {
		if (type!=null && !type.accept(v)) {
			return false;
		}
		if (name != null && !name.accept(v)) {
			return false;
		}
		if (defaultValue!=null && !defaultValue.accept(v)) {
			return false;
		}
		return true;
	}

	@Override
	/** {@inheritDoc} */
	public Declaration getDeclaration() {

		INamedNode inamedNode = getNameParent();

		while (!(inamedNode instanceof Definition)) {
			if( inamedNode == null) {
				return null; //FIXME: this is just a temp solution! find the reason!
			}
			inamedNode = inamedNode.getNameParent();
		}

		final Definition namedTemplList = (Definition) inamedNode;

		IType tempType = namedTemplList.getType(CompilationTimeStamp.getBaseTimestamp());
		if (tempType == null) {
			return null;
		}

		tempType = tempType.getTypeRefdLast(CompilationTimeStamp.getBaseTimestamp());

		if (tempType instanceof ITypeWithComponents) {
			final Identifier resultId = ((ITypeWithComponents) tempType).getComponentIdentifierByName(getIdentifier());
			return Declaration.createInstance(tempType.getDefiningAssignment(), resultId);
		}

		return null;
	}
}
