/*
 * [The "BSD license"]
 *  Copyright (c) 2014 Terence Parr
 *  Copyright (c) 2014 Sam Harwell
 *  Copyright (c) 2017 Chan Chung Kwong
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *
 *  1. Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 *  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 *  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 *  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 *  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

/**
 * A Java 9 grammar for ANTLR 4 derived from the Java Language Specification
 * chapter 19.
 *
 * NOTE: This grammar results in a generated parser that is much slower
 *       than the Java 7 grammar in the grammars-v4/java directory. This
 *     one is, however, extremely close to the spec.
 *
 * You can test with
 *
 *  $ antlr4 Java9.g4
 *  $ javac *.java
 *  $ grun Java9 compilationUnit *.java
 *
 * Or,
~/antlr/code/grammars-v4/java9 $ java Test .
/Users/parrt/antlr/code/grammars-v4/java9/./Java9BaseListener.java
/Users/parrt/antlr/code/grammars-v4/java9/./Java9Lexer.java
/Users/parrt/antlr/code/grammars-v4/java9/./Java9Listener.java
/Users/parrt/antlr/code/grammars-v4/java9/./Java9Parser.java
/Users/parrt/antlr/code/grammars-v4/java9/./Test.java
Total lexer+parser time 30844ms.
~/antlr/code/grammars-v4/java9 $ java Test examples/module-info.java
/home/kwong/projects/grammars-v4/java9/examples/module-info.java
Total lexer+parser time 914ms.
~/antlr/code/grammars-v4/java9 $ java Test examples/TryWithResourceDemo.java
/home/kwong/projects/grammars-v4/java9/examples/TryWithResourceDemo.java
Total lexer+parser time 3634ms.
~/antlr/code/grammars-v4/java9 $ java Test examples/helloworld.java
/home/kwong/projects/grammars-v4/java9/examples/helloworld.java
Total lexer+parser time 2497ms.

 */

// $antlr-format alignTrailingComments true, columnLimit 150, maxEmptyLinesToKeep 1, reflowComments false, useTab false
// $antlr-format allowShortRulesOnASingleLine true, allowShortBlocksOnASingleLine true, minEmptyLines 0, alignSemicolons ownLine
// $antlr-format alignColons trailing, singleLineOverrulesHangingColon true, alignLexerCommands true, alignLabels true, alignTrailers true

grammar Java9;

// LEXER

// §3.9 Keywords

ABSTRACT     : 'abstract';
ASSERT       : 'assert';
BOOLEAN      : 'boolean';
BREAK        : 'break';
BYTE         : 'byte';
CASE         : 'case';
CATCH        : 'catch';
CHAR         : 'char';
CLASS        : 'class';
CONST        : 'const';
CONTINUE     : 'continue';
DEFAULT      : 'default';
DO           : 'do';
DOUBLE       : 'double';
ELSE         : 'else';
ENUM         : 'enum';
EXPORTS      : 'exports';
EXTENDS      : 'extends';
FINAL        : 'final';
FINALLY      : 'finally';
FLOAT        : 'float';
FOR          : 'for';
IF           : 'if';
GOTO         : 'goto';
IMPLEMENTS   : 'implements';
IMPORT       : 'import';
INSTANCEOF   : 'instanceof';
INT          : 'int';
INTERFACE    : 'interface';
LONG         : 'long';
MODULE       : 'module';
NATIVE       : 'native';
NEW          : 'new';
OPEN         : 'open';
OPERNS       : 'opens';
PACKAGE      : 'package';
PRIVATE      : 'private';
PROTECTED    : 'protected';
PROVIDES     : 'provides';
PUBLIC       : 'public';
REQUIRES     : 'requires';
RETURN       : 'return';
SHORT        : 'short';
STATIC       : 'static';
STRICTFP     : 'strictfp';
SUPER        : 'super';
SWITCH       : 'switch';
SYNCHRONIZED : 'synchronized';
THIS         : 'this';
THROW        : 'throw';
THROWS       : 'throws';
TO           : 'to';
TRANSIENT    : 'transient';
TRANSITIVE   : 'transitive';
TRY          : 'try';
USES         : 'uses';
VOID         : 'void';
VOLATILE     : 'volatile';
WHILE        : 'while';
WITH         : 'with';
UNDER_SCORE  : '_'; //Introduced in Java 9

// §3.10.1 Integer Literals

IntegerLiteral:
    DecimalIntegerLiteral
    | HexIntegerLiteral
    | OctalIntegerLiteral
    | BinaryIntegerLiteral
;

fragment DecimalIntegerLiteral: DecimalNumeral IntegerTypeSuffix?;

fragment HexIntegerLiteral: HexNumeral IntegerTypeSuffix?;

fragment OctalIntegerLiteral: OctalNumeral IntegerTypeSuffix?;

fragment BinaryIntegerLiteral: BinaryNumeral IntegerTypeSuffix?;

fragment IntegerTypeSuffix: [lL];

fragment DecimalNumeral: '0' | NonZeroDigit (Digits? | Underscores Digits);

fragment Digits: Digit (DigitsAndUnderscores? Digit)?;

fragment Digit: '0' | NonZeroDigit;

fragment NonZeroDigit: [1-9];

fragment DigitsAndUnderscores: DigitOrUnderscore+;

fragment DigitOrUnderscore: Digit | '_';

fragment Underscores: '_'+;

fragment HexNumeral: '0' [xX] HexDigits;

fragment HexDigits: HexDigit (HexDigitsAndUnderscores? HexDigit)?;

fragment HexDigit: [0-9a-fA-F];

fragment HexDigitsAndUnderscores: HexDigitOrUnderscore+;

fragment HexDigitOrUnderscore: HexDigit | '_';

fragment OctalNumeral: '0' Underscores? OctalDigits;

fragment OctalDigits: OctalDigit (OctalDigitsAndUnderscores? OctalDigit)?;

fragment OctalDigit: [0-7];

fragment OctalDigitsAndUnderscores: OctalDigitOrUnderscore+;

fragment OctalDigitOrUnderscore: OctalDigit | '_';

fragment BinaryNumeral: '0' [bB] BinaryDigits;

fragment BinaryDigits: BinaryDigit (BinaryDigitsAndUnderscores? BinaryDigit)?;

fragment BinaryDigit: [01];

fragment BinaryDigitsAndUnderscores: BinaryDigitOrUnderscore+;

fragment BinaryDigitOrUnderscore: BinaryDigit | '_';

// §3.10.2 Floating-Point Literals

FloatingPointLiteral: DecimalFloatingPointLiteral | HexadecimalFloatingPointLiteral;

fragment DecimalFloatingPointLiteral:
    Digits '.' Digits? ExponentPart? FloatTypeSuffix?
    | '.' Digits ExponentPart? FloatTypeSuffix?
    | Digits ExponentPart FloatTypeSuffix?
    | Digits FloatTypeSuffix
;

fragment ExponentPart: ExponentIndicator SignedInteger;

fragment ExponentIndicator: [eE];

fragment SignedInteger: Sign? Digits;

fragment Sign: [+-];

fragment FloatTypeSuffix: [fFdD];

fragment HexadecimalFloatingPointLiteral: HexSignificand BinaryExponent FloatTypeSuffix?;

fragment HexSignificand: HexNumeral '.'? | '0' [xX] HexDigits? '.' HexDigits;

fragment BinaryExponent: BinaryExponentIndicator SignedInteger;

fragment BinaryExponentIndicator: [pP];

// §3.10.3 Boolean Literals

BooleanLiteral: 'true' | 'false';

// §3.10.4 Character Literals

CharacterLiteral: '\'' SingleCharacter '\'' | '\'' EscapeSequence '\'';

fragment SingleCharacter: ~['\\\r\n];

// §3.10.5 String Literals

StringLiteral: '"' StringCharacters? '"';

fragment StringCharacters: StringCharacter+;

fragment StringCharacter: ~["\\\r\n] | EscapeSequence;

// §3.10.6 Escape Sequences for Character and String Literals

fragment EscapeSequence:
    '\\' 'u005c'? [btnfr"'\\]
    | OctalEscape
    | UnicodeEscape // This is not in the spec but prevents having to preprocess the input
;

fragment OctalEscape:
    '\\' 'u005c'? OctalDigit
    | '\\' 'u005c'? OctalDigit OctalDigit
    | '\\' 'u005c'? ZeroToThree OctalDigit OctalDigit
;

fragment ZeroToThree: [0-3];

// This is not in the spec but prevents having to preprocess the input
fragment UnicodeEscape: '\\' 'u'+ HexDigit HexDigit HexDigit HexDigit;

// §3.10.7 The Null Literal

NullLiteral: 'null';

// §3.11 Separators

LPAREN     : '(';
RPAREN     : ')';
LBRACE     : '{';
RBRACE     : '}';
LBRACK     : '[';
RBRACK     : ']';
SEMI       : ';';
COMMA      : ',';
DOT        : '.';
ELLIPSIS   : '...';
AT         : '@';
COLONCOLON : '::';

// §3.12 Operators

ASSIGN   : '=';
GT       : '>';
LT       : '<';
BANG     : '!';
TILDE    : '~';
QUESTION : '?';
COLON    : ':';
ARROW    : '->';
EQUAL    : '==';
LE       : '<=';
GE       : '>=';
NOTEQUAL : '!=';
AND      : '&&';
OR       : '||';
INC      : '++';
DEC      : '--';
ADD      : '+';
SUB      : '-';
MUL      : '*';
DIV      : '/';
BITAND   : '&';
BITOR    : '|';
CARET    : '^';
MOD      : '%';
//LSHIFT : '<<';
//RSHIFT : '>>';
//URSHIFT : '>>>';

ADD_ASSIGN     : '+=';
SUB_ASSIGN     : '-=';
MUL_ASSIGN     : '*=';
DIV_ASSIGN     : '/=';
AND_ASSIGN     : '&=';
OR_ASSIGN      : '|=';
XOR_ASSIGN     : '^=';
MOD_ASSIGN     : '%=';
LSHIFT_ASSIGN  : '<<=';
RSHIFT_ASSIGN  : '>>=';
URSHIFT_ASSIGN : '>>>=';

// §3.8 Identifiers (must appear after all keywords in the grammar)

Identifier: JavaLetter JavaLetterOrDigit*;

fragment JavaLetter:
    [a-zA-Z$_]                      // these are the "java letters" below 0x7F
    |                               // covers all characters above 0x7F which are not a surrogate
    ~[\u0000-\u007F\uD800-\uDBFF]   { Check1() }?
    |                               // covers UTF-16 surrogate pairs encodings for U+10000 to U+10FFFF
    [\uD800-\uDBFF] [\uDC00-\uDFFF] { Check2() }?
;

fragment JavaLetterOrDigit:
    [a-zA-Z0-9$_]                   // these are the "java letters or digits" below 0x7F
    |                               // covers all characters above 0x7F which are not a surrogate
    ~[\u0000-\u007F\uD800-\uDBFF]   { Check3() }?
    |                               // covers UTF-16 surrogate pairs encodings for U+10000 to U+10FFFF
    [\uD800-\uDBFF] [\uDC00-\uDFFF] { Check4() }?
;

//
// Whitespace and comments
//

WS: [ \t\r\n\u000C]+ -> skip;

COMMENT: '/*' .*? '*/' -> channel(HIDDEN);

LINE_COMMENT: '//' ~[\r\n]* -> channel(HIDDEN);

// PARSER RULES

// Top-level rule
compilationUnit
    : packageDeclaration? importDeclaration* typeDeclaration* EOF
    ;

packageDeclaration
    : annotation* PACKAGE qualifiedName SEMI
    ;

importDeclaration
    : IMPORT STATIC? qualifiedName (DOT MUL)? SEMI
    ;

typeDeclaration
    : classOrInterfaceModifier*
      (classDeclaration | enumDeclaration | interfaceDeclaration | annotationTypeDeclaration | moduleDeclaration)
    | SEMI
    ;

// Modifiers
classOrInterfaceModifier
    : annotation | PUBLIC | PROTECTED | PRIVATE | STATIC | ABSTRACT | FINAL | STRICTFP
    ;

// Classes
classDeclaration
    : CLASS Identifier typeParameters?
      (EXTENDS typeType)?
      (IMPLEMENTS typeList)?
      classBody
    ;

typeParameters
    : LT typeParameter (COMMA typeParameter)* GT
    ;

typeParameter
    : annotation* Identifier (EXTENDS typeBound)?
    ;

typeBound
    : typeType (BITAND typeType)*
    ;

classBody
    : LBRACE classBodyDeclaration* RBRACE
    ;

classBodyDeclaration
    : SEMI
    | STATIC? block
    | classOrInterfaceModifier* memberDeclaration
    ;

memberDeclaration
    : methodDeclaration
    | fieldDeclaration
    | constructorDeclaration
    | classDeclaration
    | interfaceDeclaration
    | annotationTypeDeclaration
    | enumDeclaration
    ;

// Methods
methodDeclaration
    : typeTypeOrVoid Identifier formalParameters (LBRACK RBRACK)*
      (THROWS qualifiedNameList)?
      methodBody
    ;

methodBody
    : block
    | SEMI
    ;

typeTypeOrVoid
    : typeType
    | VOID
    ;

constructorDeclaration
    : Identifier formalParameters (THROWS qualifiedNameList)? constructorBody
    ;

constructorBody
    : block
    ;

// Fields
fieldDeclaration
    : typeType variableDeclarators SEMI
    ;

variableDeclarators
    : variableDeclarator (COMMA variableDeclarator)*
    ;

variableDeclarator
    : variableDeclaratorId (ASSIGN variableInitializer)?
    ;

variableDeclaratorId
    : Identifier (LBRACK RBRACK)*
    ;

variableInitializer
    : arrayInitializer
    | expression
    ;

arrayInitializer
    : LBRACE (variableInitializer (COMMA variableInitializer)* COMMA?)? RBRACE
    ;

// Interfaces
interfaceDeclaration
    : INTERFACE Identifier typeParameters? (EXTENDS typeList)? interfaceBody
    ;

interfaceBody
    : LBRACE interfaceBodyDeclaration* RBRACE
    ;

interfaceBodyDeclaration
    : classOrInterfaceModifier* interfaceMemberDeclaration
    | SEMI
    ;

interfaceMemberDeclaration
    : constDeclaration
    | interfaceMethodDeclaration
    | interfaceDeclaration
    | annotationTypeDeclaration
    | classDeclaration
    | enumDeclaration
    ;

constDeclaration
    : typeType constantDeclarator (COMMA constantDeclarator)* SEMI
    ;

constantDeclarator
    : Identifier (LBRACK RBRACK)* ASSIGN variableInitializer
    ;

interfaceMethodDeclaration
    : interfaceMethodModifier* typeTypeOrVoid Identifier formalParameters (LBRACK RBRACK)* (THROWS qualifiedNameList)? methodBody
    ;

interfaceMethodModifier
    : annotation | PUBLIC | ABSTRACT | DEFAULT | STATIC | STRICTFP
    ;

// Enums
enumDeclaration
    : ENUM Identifier (IMPLEMENTS typeList)? LBRACE enumConstants? COMMA? enumBodyDeclarations? RBRACE
    ;

enumConstants
    : enumConstant (COMMA enumConstant)*
    ;

enumConstant
    : annotation* Identifier arguments? classBody?
    ;

enumBodyDeclarations
    : SEMI classBodyDeclaration*
    ;

// Annotations
annotationTypeDeclaration
    : AT INTERFACE Identifier annotationTypeBody
    ;

annotationTypeBody
    : LBRACE annotationTypeElementDeclaration* RBRACE
    ;

annotationTypeElementDeclaration
    : classOrInterfaceModifier* annotationTypeElementRest
    | SEMI
    ;

annotationTypeElementRest
    : typeTypeOrVoid Identifier annotationMethodRest
    | typeType Identifier annotationConstantRest
    | classDeclaration
    | interfaceDeclaration
    | enumDeclaration
    | annotationTypeDeclaration
    ;

annotationMethodRest
    : LPAREN RPAREN defaultValue?
    ;

annotationConstantRest
    : variableDeclarators
    ;

defaultValue
    : DEFAULT expression
    ;

// Modules (Java 9)
moduleDeclaration
    : OPEN? MODULE qualifiedName LBRACE moduleDirective* RBRACE
    ;

moduleDirective
    : REQUIRES requiresModifier* qualifiedName SEMI
    | EXPORTS qualifiedName (TO qualifiedName (COMMA qualifiedName)*)? SEMI
    | OPERNS qualifiedName (TO qualifiedName (COMMA qualifiedName)*)? SEMI
    | USES qualifiedName SEMI
    | PROVIDES qualifiedName WITH qualifiedName (COMMA qualifiedName)* SEMI
    ;

requiresModifier
    : TRANSITIVE | STATIC
    ;

// Formal parameters
formalParameters
    : LPAREN formalParameterList? RPAREN
    ;

formalParameterList
    : formalParameter (COMMA formalParameter)* (COMMA lastFormalParameter)?
    | lastFormalParameter
    ;

formalParameter
    : variableModifier* typeType variableDeclaratorId
    ;

lastFormalParameter
    : variableModifier* typeType ELLIPSIS variableDeclaratorId
    ;

variableModifier
    : FINAL
    | annotation
    ;

// Blocks and Statements
block
    : LBRACE blockStatement* RBRACE
    ;

blockStatement
    : localVariableDeclaration SEMI
    | statement
    | localTypeDeclaration
    ;

localVariableDeclaration
    : variableModifier* typeType variableDeclarators
    ;

localTypeDeclaration
    : classOrInterfaceModifier* (classDeclaration | interfaceDeclaration)
    | SEMI
    ;

statement
    : blockLabel=block
    | ASSERT expression (COLON expression)? SEMI
    | IF parExpression statement (ELSE statement)?
    | FOR LPAREN forControl RPAREN statement
    | WHILE parExpression statement
    | DO statement WHILE parExpression SEMI
    | TRY block (catchClause+ finallyBlock? | finallyBlock)
    | TRY resourceSpecification block catchClause* finallyBlock?
    | SWITCH parExpression LBRACE switchBlockStatementGroup* switchLabel* RBRACE
    | SYNCHRONIZED parExpression block
    | RETURN expression? SEMI
    | THROW expression SEMI
    | BREAK Identifier? SEMI
    | CONTINUE Identifier? SEMI
    | SEMI
    | statementExpression=expression SEMI
    | identifierLabel=Identifier COLON statement
    ;

catchClause
    : CATCH LPAREN variableModifier* catchType Identifier RPAREN block
    ;

catchType
    : qualifiedName (BITOR qualifiedName)*
    ;

finallyBlock
    : FINALLY block
    ;

resourceSpecification
    : LPAREN resources SEMI? RPAREN
    ;

resources
    : resource (SEMI resource)*
    ;

resource
    : variableModifier* (classOrInterfaceType variableDeclaratorId | variableAccess) ASSIGN expression
    ;

switchBlockStatementGroup
    : switchLabel+ blockStatement+
    ;

switchLabel
    : CASE (constantExpression=expression | enumConstantName=Identifier) COLON
    | DEFAULT COLON
    ;

forControl
    : enhancedForControl
    | forInit? SEMI expression? SEMI forUpdate=expressionList?
    ;

forInit
    : localVariableDeclaration
    | expressionList
    ;

enhancedForControl
    : variableModifier* typeType variableDeclaratorId COLON expression
    ;

// Expressions
parExpression
    : LPAREN expression RPAREN
    ;

expressionList
    : expression (COMMA expression)*
    ;

expression
    : primary
    | expression DOT Identifier
    | expression DOT THIS
    | expression DOT NEW nonWildcardTypeArguments? innerCreator
    | expression DOT SUPER superSuffix
    | expression DOT explicitGenericInvocation
    | expression LBRACK expression RBRACK
    | expression LPAREN expressionList? RPAREN
    | NEW creator
    | LPAREN typeType RPAREN expression
    | expression (INC | DEC)
    | (ADD | SUB | INC | DEC) expression
    | (TILDE | BANG) expression
    | expression (MUL | DIV | MOD) expression
    | expression (ADD | SUB) expression
    | expression (LT LT | GT GT GT | GT GT) expression
    | expression (LE | GE | GT | LT) expression
    | expression INSTANCEOF typeType
    | expression (EQUAL | NOTEQUAL) expression
    | expression BITAND expression
    | expression CARET expression
    | expression BITOR expression
    | expression AND expression
    | expression OR expression
    | expression QUESTION expression COLON expression
    | <assoc=right> expression
      (ASSIGN | ADD_ASSIGN | SUB_ASSIGN | MUL_ASSIGN | DIV_ASSIGN | AND_ASSIGN | OR_ASSIGN | XOR_ASSIGN | MOD_ASSIGN | LSHIFT_ASSIGN | RSHIFT_ASSIGN | URSHIFT_ASSIGN)
      expression
    | lambdaExpression
    ;

// Lambda expressions (Java 8)
lambdaExpression
    : lambdaParameters ARROW lambdaBody
    ;

lambdaParameters
    : Identifier
    | LPAREN formalParameterList? RPAREN
    | LPAREN Identifier (COMMA Identifier)* RPAREN
    ;

lambdaBody
    : expression
    | block
    ;

primary
    : LPAREN expression RPAREN
    | THIS
    | SUPER
    | literal
    | Identifier
    | typeTypeOrVoid DOT CLASS
    | nonWildcardTypeArguments (explicitGenericInvocationSuffix | THIS arguments)
    ;

classType
    : (classOrInterfaceType DOT)? annotation* Identifier typeArguments?
    ;

creator
    : nonWildcardTypeArguments createdName classCreatorRest
    | createdName (arrayCreatorRest | classCreatorRest)
    ;

createdName
    : Identifier typeArgumentsOrDiamond? (DOT Identifier typeArgumentsOrDiamond?)*
    | primitiveType
    ;

innerCreator
    : Identifier nonWildcardTypeArgumentsOrDiamond? classCreatorRest
    ;

arrayCreatorRest
    : LBRACK (RBRACK (LBRACK RBRACK)* arrayInitializer | expression RBRACK (LBRACK expression RBRACK)* (LBRACK RBRACK)*)
    ;

classCreatorRest
    : arguments classBody?
    ;

explicitGenericInvocation
    : nonWildcardTypeArguments explicitGenericInvocationSuffix
    ;

typeArgumentsOrDiamond
    : LT GT
    | typeArguments
    ;

nonWildcardTypeArgumentsOrDiamond
    : LT GT
    | nonWildcardTypeArguments
    ;

nonWildcardTypeArguments
    : LT typeList GT
    ;

typeList
    : typeType (COMMA typeType)*
    ;

typeType
    : annotation* (classOrInterfaceType | primitiveType) (LBRACK RBRACK)*
    ;

primitiveType
    : BOOLEAN
    | CHAR
    | BYTE
    | SHORT
    | INT
    | LONG
    | FLOAT
    | DOUBLE
    ;

typeArguments
    : LT typeArgument (COMMA typeArgument)* GT
    ;

superSuffix
    : arguments
    | DOT Identifier arguments?
    ;

explicitGenericInvocationSuffix
    : SUPER superSuffix
    | Identifier arguments
    ;

arguments
    : LPAREN expressionList? RPAREN
    ;

// Type and Name Help
typeArgument
    : typeType
    | QUESTION ((EXTENDS | SUPER) typeType)?
    ;

annotation
    : AT qualifiedName (LPAREN (elementValuePairs | elementValue)? RPAREN)?
    ;

elementValuePairs
    : elementValuePair (COMMA elementValuePair)*
    ;

elementValuePair
    : Identifier ASSIGN elementValue
    ;

elementValue
    : expression
    | annotation
    | elementValueArrayInitializer
    ;

elementValueArrayInitializer
    : LBRACE (elementValue (COMMA elementValue)*)? COMMA? RBRACE
    ;

classOrInterfaceType
    : Identifier typeArguments? (DOT Identifier typeArguments?)*
    ;

qualifiedNameList
    : qualifiedName (COMMA qualifiedName)*
    ;

qualifiedName
    : Identifier (DOT Identifier)*
    ;

variableAccess
    : Identifier
    | THIS
    | SUPER
    ;

literal
    : IntegerLiteral
    | FloatingPointLiteral
    | CharacterLiteral
    | StringLiteral
    | BooleanLiteral
    | NullLiteral
    ;