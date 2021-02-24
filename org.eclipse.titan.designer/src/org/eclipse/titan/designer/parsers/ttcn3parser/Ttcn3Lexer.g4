lexer grammar Ttcn3Lexer;
import Ttcn3BaseLexer;

/*
 ******************************************************************************
 * Copyright (c) 2000-2021 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************
*/

/*
 * author Arpad Lovassy
 */

@header
{
}

@members {
  protected boolean realtimeEnabled = false;

  public void enableRealtime() {
  	realtimeEnabled = true;
  }
}

// Overriding tokens inherited from Ttcn3BaseLexer.g4
// NOTE: Ttcn3 lexers must contain the same amount of tokens to make sure, that they are synchronized properly.
//       Token index of the same token must be the same in all of the Ttcn3 lexers, otherwise code completion
//       will not work properly, because Ttcn3ReferenceParser uses Ttcn3KeywordlessLexer (based on Ttcn3BaseLexer).
//       So if a new token is added to Ttcn3Lexer, the same token must be added also to Ttcn3BaseLexer as unused token
//       (see "tokens" section in Ttcn3BaseLexer.g4).

/*------------------------------------------- Keywords -------------------------------------------*/

  ACTION: 'action';                 ACTIVATE: 'activate';             ADDRESS: 'address';
  ALIVE: 'alive';                   ALL: 'all';                       ALT: 'alt';
  ALTSTEP: 'altstep';               AND: 'and';                       AND4B: 'and4b';
  ANY: 'any';                       ANYTYPE: 'anytype';               ANY2UNISTR: 'any2unistr';
  APPLY: 'apply';

  BITSTRING: 'bitstring';           BOOLEAN: 'boolean';               BREAK: 'break';

  CALL: 'call';                     CASE: 'case';                     CATCH: 'catch';
  CHARKEYWORD: 'char';              CHARSTRING: 'charstring';         CHECK: 'check';
  CHECKSTATE: 'checkstate';         CLASS: 'class';
  CLEAR: 'clear';                   COMPLEMENTKEYWORD: 'complement';
  COMPONENT: 'component';           CONNECT: 'connect';               CONST: 'const';
  CONTINUE: 'continue';             CONTROL: 'control';               CREATE: 'create';

  DEACTIVATE: 'deactivate';         DEFAULT: 'default';               DECMATCH: 'decmatch';
  DECVALUE: 'decvalue';             DECVALUE_UNICHAR: 'decvalue_unichar';  DEREFERS: 'derefers';
  DISCONNECT: 'disconnect';         DISPLAY: 'display';               DO: 'do';
  DONE: 'done';

  ELSE: 'else';                     ENCODE: 'encode';                 ENCVALUE: 'encvalue';
  ENCVALUE_UNICHAR: 'encvalue_unichar';  ENUMERATED: 'enumerated';    ERROR: 'error';
  EXCEPT: 'except';                 EXCEPTION: 'exception';           EXECUTE: 'execute';
  EXTENDS: 'extends';               EXTENSION: 'extension';           EXTERNAL: 'external';

  FAIL: 'fail';                     FALSE: 'false';                   FINALLY: 'finally';
  FLOAT: 'float';
  FOR: 'for';                       FRIEND: 'friend';                 FROM: 'from';
  FUNCTION: 'function';

  GETCALL: 'getcall';               GETREF: 'getref';                 GETREPLY: 'getreply';
  GETVERDICT: 'getverdict';         GOTO: 'goto';                     GROUP: 'group';

  HALT: 'halt';                     HEXSTRING: 'hexstring';           HOSTID: 'hostid';

  IF: 'if';                         IFPRESENT: 'ifpresent';           IMPORT: 'import';
  IN: 'in';                         INCONC: 'inconc';                 INFINITY: 'infinity';
  INOUT: 'inout';                   INTEGER: 'integer';               INTERLEAVE: 'interleave';
  ISTEMPLATEKIND: 'istemplatekind';

  KILL: 'kill';                     KILLED: 'killed';

  LABEL: 'label';                   LANGUAGE: 'language';             LENGTH: 'length';
  LOG: 'log';

  MAP: 'map';                       MATCH: 'match';                   MESSAGE: 'message';
  MIXED: 'mixed';                   MOD: 'mod';                       MODIFIES: 'modifies';
  MODULE: 'module';                 MODULEPAR: 'modulepar';           MTC: 'mtc';

  NOBLOCK: 'noblock';               NONE: 'none';                     NOT: 'not';
  NOT4B: 'not4b';                   NOWAIT: 'nowait';
  NOT_A_NUMBER: 'not_a_number';     NULL1: 'null';                    NULL2: 'NULL';

  OBJECTIDENTIFIERKEYWORD: 'objid'; OBJECTKEYWORD: 'object';          OCTETSTRING: 'octetstring';       
  OF: 'of';
  OMIT: 'omit';                     ON: 'on';                         OPTIONAL: 'optional';
  OR: 'or';                         OR4B: 'or4b';                     OUT: 'out';
  OVERRIDEKEYWORD: 'override';

  PARAM: 'param';                   PASS: 'pass';                     PATTERNKEYWORD: 'pattern';
  PERMUTATION: 'permutation';       PORT: 'port';                     PUBLIC: 'public';
  PRESENT: 'present';               PRIVATE: 'private';               PROCEDURE: 'procedure';
  PROTECTED: 'protected';

  RAISE: 'raise';                   READ: 'read';                     RECEIVE: 'receive';
  RECORD: 'record';                 RECURSIVE: 'recursive';           REFERS: 'refers';
  REM: 'rem';                       REPEAT: 'repeat';                 REPLY: 'reply';
  RETURN: 'return';                 RUNNING: 'running';               RUNS: 'runs';

  SELECT: 'select';                 SELF: 'self';                     SEND: 'send';
  SENDER: 'sender';                 SET: 'set';                       SETVERDICT: 'setverdict';
  SETSTATE: 'setstate';             SIGNATURE: 'signature';           START: 'start';
  STOP: 'stop';                     SUBSET: 'subset';                 SUPERSET: 'superset';
  SYSTEM: 'system';

  TEMPLATE: 'template';             TESTCASE: 'testcase';             TIMEOUT: 'timeout';
  TIMER: 'timer';                   TO: 'to';                         TRIGGER: 'trigger';
  TRUE: 'true';                     TYPE: 'type';

  UNION: 'union';                   UNIVERSAL: 'universal';           UNMAP: 'unmap';

  VALUE: 'value';                   VALUEOF: 'valueof';               VAR: 'var';
  VARIANT: 'variant';               VERDICTTYPE: 'verdicttype';

  WHILE: 'while';                   WITH: 'with';

  XOR: 'xor';                       XOR4B: 'xor4b';


  /*------------------------------ Predefined function identifiers --------------------------------*/

  BIT2HEX: 'bit2hex';               BIT2INT: 'bit2int';               BIT2OCT: 'bit2oct';
  BIT2STR: 'bit2str';               BSON2JSON: 'bson2json';

  CBOR2JSON: 'cbor2json';           CHAR2INT: 'char2int';             CHAR2OCT: 'char2oct';

  DECODE_BASE64: 'decode_base64';   DECOMP: 'decomp';

  ENCODE_BASE64: 'encode_base64';   ENUM2INT: 'enum2int';

  FLOAT2INT: 'float2int';           FLOAT2STR: 'float2str';

  GET_STRINGENCODING: 'get_stringencoding';

  HEX2BIT: 'hex2bit';               HEX2INT: 'hex2int';               HEX2OCT: 'hex2oct';
  HEX2STR: 'hex2str';

  INT2BIT: 'int2bit';               INT2CHAR: 'int2char';             INT2ENUM: 'int2enum';
  INT2FLOAT: 'int2float';           INT2HEX: 'int2hex';               INT2OCT: 'int2oct';
  INT2STR: 'int2str';               INT2UNICHAR: 'int2unichar';       ISBOUND: 'isbound';
  ISCHOSEN: 'ischosen';             ISPRESENT: 'ispresent';           ISVALUE: 'isvalue';

  JSON2BSON: 'json2bson';           JSON2CBOR: 'json2cbor';

  LENGTHOF: 'lengthof';             LOG2STR: 'log2str';

  OCT2BIT: 'oct2bit';               OCT2CHAR: 'oct2char';             OCT2HEX: 'oct2hex';
  OCT2INT: 'oct2int';               OCT2STR: 'oct2str';               OCT2UNICHAR: 'oct2unichar';

  REGEXP: 'regexp';                 REMOVE_BOM: 'remove_bom';         RND: 'rnd';
  REPLACE: 'replace';

  SETENCODE: 'setencode';           SIZEOF: 'sizeof';                 STR2BIT: 'str2bit';
  STR2FLOAT: 'str2float';           STR2HEX: 'str2hex';               STR2INT: 'str2int';
  STR2OCT: 'str2oct';               STRING2TTCN: 'string2ttcn';       SUBSTR: 'substr';

  TESTCASENAME: 'testcasename';     TTCN2STRING: 'ttcn2string';

  UNICHAR2CHAR: 'unichar2char';     UNICHAR2INT: 'unichar2int';       UNICHAR2OCT: 'unichar2oct';

/*------------------------------ Predefined function identifiers --------------------------------*/

NOW: 'now'
{if (!realtimeEnabled) {
reportWarning( "Keyword 'now' is treated as an identifier. Activate real-time testing features in the On-the-fly checker's settings.", getToken(), getToken() );
setType(IDENTIFIER);
}
};

REALTIME: 'realtime'
{if (!realtimeEnabled) {
reportWarning( "Keyword 'realtime' is treated as an identifier. Activate real-time testing features in the On-the-fly checker's settings.", getToken(), getToken() );
setType(IDENTIFIER);
}
};

TIMESTAMP: 'timestamp'
{if (!realtimeEnabled) {
reportWarning( "Keyword 'timestamp' is treated as an identifier. Activate real-time testing features in the On-the-fly checker's settings.", getToken(), getToken() );
setType(IDENTIFIER);
}
};
