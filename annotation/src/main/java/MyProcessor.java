import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.TypeVariableName;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.Pair;
import picocli.CommandLine;
import picocli.codegen.annotation.processing.AbstractCommandSpecProcessor;

import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class MyProcessor extends AbstractCommandSpecProcessor {
    private Map<Symbol, TypeSpec.Builder> classes = new HashMap<>();

    @Override
    protected boolean handleCommands(Map<Element, CommandLine.Model.CommandSpec> commands, Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element elem : commands.keySet()) {
            CommandLine.Model.CommandSpec cmd = commands.get(elem);
            Symbol cmdSym = (Symbol) elem;

            TypeSpec.Builder cmdClass;
            if (cmdSym instanceof Symbol.ClassSymbol) {
                cmdClass = classes.get(cmdSym);
            } else if (cmdSym instanceof Symbol.MethodSymbol) {
                cmdClass = classes.get(cmdSym.owner);
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Unsupported symbol type: " + cmdSym.kind);
                continue;
            }

            if (cmdClass == null) {
                // GEN public abstract class XxxxCmdSpecFact
                ClassName factClassName = factClassName(cmdSym);
                cmdClass = TypeSpec.classBuilder(factClassName)
                        .addModifiers(modifiers(cmdSym))
                        .addModifiers(Modifier.ABSTRACT);
                classes.put(cmdSym, cmdClass);
            }

            TypeName cmdSpecType = ClassName.get(CommandLine.Model.CommandSpec.class);
            ClassName cmdClassName = cmdClassName(cmdSym);

            if (isCommand(cmdSym)) {
                // GEN public static CommandLine.Model.CommandSpec createCmdSpec()
                CommandLine.Command cmdAnno = cmdSym.getAnnotation(CommandLine.Command.class);
                MethodSpec.Builder factMth = MethodSpec.methodBuilder(getCreateCmdSpecName(cmdSym))
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(cmdSpecType);

                String objName;
                if (cmdSym instanceof Symbol.ClassSymbol) {
                    factMth.addStatement("$T obj = new $T()", cmdClassName, cmdClassName);
                    objName = "obj";
                } else {
                    Symbol.MethodSymbol mthSym = (Symbol.MethodSymbol) cmdSym;
                    factMth.addParameter(cmdClassName, "obj");
                    factMth.addStatement("$T mth = null", Method.class);
                    factMth.addCode("try { mth = obj.getClass().getDeclaredMethod($S", mthSym.name);
                    for (Symbol.VarSymbol param : mthSym.params()) {
                        factMth.addCode(", $T.class", processingEnv.getTypeUtils().erasure(param.asType()));
                    }
                    factMth.addCode("); } catch ($T ex) { throw new $T(ex); }\n", NoSuchMethodException.class, RuntimeException.class);
                    objName = "mth";
                }
                factMth.addStatement("$T cs = $T.wrapWithoutInspection($N)", CommandLine.Model.CommandSpec.class, CommandLine.Model.CommandSpec.class, objName)
                        .addStatement("cs.name($S)", cmdAnno.name())
                        .addStatement("cs.usageMessage().description(" + stringsArg(cmdAnno.description()) + ")", (Object[]) cmdAnno.description());

                addSubcommands(factMth, cmd.subcommands());

                addConfigureCall(factMth, null, getConfigureAllName(cmdSym), "cs", "obj");

                factMth.addStatement("return cs");

                cmdClass.addMethod(factMth.build());
            }

            Symbol superClassSym = getSuperClass(cmdSym, commands.values());

            configureAll(cmdClass, cmdSym, superClassSym, cmd);
            configureMixins(cmdClass, cmdSym, cmd.mixins());
            configureOptions(cmdClass, cmdSym, directArgs(cmd.options(), cmdSym));
            configureParameters(cmdClass, cmdSym, directArgs(cmd.positionalParameters(), cmdSym));
            configureArgGroups(cmdClass, cmdSym, cmd.argGroups());
            configureSpecs(cmdClass, cmdSym, cmd.specElements());
            configureParentCommands(cmdClass, cmdSym, cmd.parentCommandElements());
            configureUnmatcheds(cmdClass, cmdSym, cmd.unmatchedArgsBindings());
        }

//        if (roundEnv.processingOver()) {
        if (!commands.isEmpty()) {
            // Make sure classes are properly nested
            List<Symbol> sortedSyms = depthSort(classes.keySet());
            for (Symbol cmdSym : sortedSyms) {
                if (cmdSym.owner instanceof Symbol.ClassSymbol) {
                    TypeSpec.Builder outer = classes.get(cmdSym.owner);
                    if (outer != null) {
                        TypeSpec.Builder cmdClass = classes.get(cmdSym);
                        outer.addType(cmdClass.build());
                    }
                }
            }

            // Write top level classes to their files
            for (Map.Entry<Symbol, TypeSpec.Builder> e : classes.entrySet()) {
                Symbol cmdSym = e.getKey();
                TypeSpec.Builder cmdClass = e.getValue();
                if (cmdSym.owner instanceof Symbol.PackageSymbol) {
                    try {
                        JavaFile.builder(factClassName(cmdSym).packageName(), cmdClass.build()).build().writeTo(processingEnv.getFiler());
                    } catch (IOException ex) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Could not generate code for " + cmdSym + ": " + ex.getMessage());
                    }
                }
            }
        }

        return false;
    }

    private void addSubcommands(MethodSpec.Builder factMth, Map<String, CommandLine> subcommands) {
        for (String subName : subcommands.keySet()) {
            CommandLine sub = subcommands.get(subName);
            Symbol subSym = (Symbol) sub.getCommandSpec().userObject();
            String param = subSym instanceof Symbol.MethodSymbol ? "obj" : "";
            factMth.addStatement("cs.addSubcommand($S, $T.$L($L))", subName, factClassName(subSym), getCreateCmdSpecName(subSym), param);
        }
    }

    private void configureAll(TypeSpec.Builder cmdClass, Symbol sym, Symbol superClassSym, CommandLine.Model.CommandSpec cmd) {
        String mthName = getConfigureAllName(sym);
        TypeName cmdSpecType = ClassName.get(CommandLine.Model.CommandSpec.class);
        ClassName cmdClassName = cmdClassName(sym);
        MethodSpec.Builder cfgMth = MethodSpec.methodBuilder(mthName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(cmdSpecType, "cs")
                .addParameter(cmdClassName, "obj");
        // If we have a super class with annotations we add a call to its configure methods
        if (superClassSym != null) {
            addConfigureCall(cfgMth, superClassSym, getConfigureAllName(superClassSym), "cs", "obj");
        }
        addConfigureCall(cfgMth, null, getConfigureMixinsName(sym), "cs", "obj");
        addConfigureCall(cfgMth, null, getConfigureOptionsName(sym), "cs::addOption", "obj");
        addConfigureCall(cfgMth, null, getConfigureParametersName(sym), "cs::addPositional", "obj");
        addConfigureCall(cfgMth, null, getConfigureArgGroupsName(sym), "cs", "obj");
        addConfigureCall(cfgMth, null, getConfigureSpecsName(sym), "cs", "obj");
        addConfigureCall(cfgMth, null, getConfigureParentCommandsName(sym), "cs", "obj");
        addConfigureCall(cfgMth, null, getConfigureUnmatchedsName(sym), "cs", "obj");
        cmdClass.addMethod(cfgMth.build());
    }

    private void configureUnmatcheds(TypeSpec.Builder cmdClass, Symbol sym, Collection<CommandLine.Model.UnmatchedArgsBinding> unmatcheds) {
        String mthName = getConfigureUnmatchedsName(sym);
        MethodSpec.Builder cfgMth = configMethod(sym, mthName);
        addUnmatcheds(cfgMth, unmatcheds);
        cmdClass.addMethod(cfgMth.build());
    }

    private void addUnmatcheds(MethodSpec.Builder cfgMth, Collection<CommandLine.Model.UnmatchedArgsBinding> unmatcheds) {
        for (CommandLine.Model.UnmatchedArgsBinding unmatched : unmatcheds) {
            addUnmatched(cfgMth, unmatched);
        }
    }

    private void configureParentCommands(TypeSpec.Builder cmdClass, Symbol sym, Collection<CommandLine.Model.IAnnotatedElement> elems) {
        String mthName = getConfigureParentCommandsName(sym);
        MethodSpec.Builder cfgMth = configMethod(sym, mthName);
        addParentCommands(cfgMth, elems);
        cmdClass.addMethod(cfgMth.build());
    }

    private void addParentCommands(MethodSpec.Builder cfgMth, Collection<CommandLine.Model.IAnnotatedElement> elems) {
        for (CommandLine.Model.IAnnotatedElement elem : elems) {
            cfgMth.addStatement("obj.$N = cs.parent()", elem.getName());
        }
    }

    private void configureSpecs(TypeSpec.Builder cmdClass, Symbol sym, Collection<CommandLine.Model.IAnnotatedElement> elems) {
        String mthName = getConfigureSpecsName(sym);
        MethodSpec.Builder cfgMth = configMethod(sym, mthName);
        addSpecs(cfgMth, elems);
        cmdClass.addMethod(cfgMth.build());
    }

    private void addSpecs(MethodSpec.Builder cfgMth, Collection<CommandLine.Model.IAnnotatedElement> elems) {
        for (CommandLine.Model.IAnnotatedElement elem : elems) {
            cfgMth.addStatement("obj.$N = cs", elem.getName());
        }
    }

    private void configureArgGroups(TypeSpec.Builder cmdClass, Symbol sym, Collection<CommandLine.Model.ArgGroupSpec> groups) {
        String mthName = getConfigureArgGroupsName(sym);
        MethodSpec.Builder cfgMth = configMethod(sym, mthName);
        addArgGroups(cfgMth, sym, groups);
        cmdClass.addMethod(cfgMth.build());
    }

    private void addArgGroups(MethodSpec.Builder cfgMth, Symbol sym, Collection<CommandLine.Model.ArgGroupSpec> argGroups) {
        for (CommandLine.Model.ArgGroupSpec argGroup : argGroups) {
            addArgGroup(cfgMth, (Symbol.ClassSymbol) sym, argGroup);
        }
    }

    private void addArgGroup(MethodSpec.Builder cfgMth, Symbol.ClassSymbol sym, CommandLine.Model.ArgGroupSpec argGroup) {
        // HACK Very hacky way to get a name for the arg group we're creating
        CommandLine.Model.ArgSpec firstArgOpt = argGroup.args().iterator().next();
        Symbol firstArgSym = (Symbol) firstArgOpt.userObject();
        Type argGroupType = firstArgSym.owner.type;

        //HACK Pretty hacky way to associate the ArgGroup with the field it was defined on
        StreamSupport.stream(sym.members().getSymbols().spliterator(), false)
                .filter(sf -> sf.type.equals(argGroupType) && sf.getAnnotation(CommandLine.ArgGroup.class) != null)
                .forEach(sf -> {
                    String fieldName = sf.name.toString();
                    String varName = fieldName + "ArgGroup";
                    cfgMth.addStatement("$T $N = $T.builder()", CommandLine.Model.ArgGroupSpec.Builder.class, varName, CommandLine.Model.ArgGroupSpec.class);
                    addConfigureCall(cfgMth, firstArgSym.owner, getConfigureOptionsName(firstArgSym.owner), varName + "::addArg", "obj." + fieldName);
                    addConfigureCall(cfgMth, firstArgSym.owner, getConfigureParametersName(firstArgSym.owner), varName + "::addArg", "obj." + fieldName);
                    cfgMth.addStatement("cs.addArgGroup($N.build())", varName);
                });

    }

    private void configureMixins(TypeSpec.Builder cmdClass, Symbol sym, Map<String, CommandLine.Model.CommandSpec> mixins) {
        String mthName = getConfigureMixinsName(sym);
        MethodSpec.Builder cfgMth = configMethod(sym, mthName);
        addMixins(cfgMth, mixins);
        cmdClass.addMethod(cfgMth.build());
    }

    private void addMixins(MethodSpec.Builder cfgMth, Map<String, CommandLine.Model.CommandSpec> mixins) {
        // If we have mixins we add a call to their configure methods
        for (String mixinName : mixins.keySet()) {
            CommandLine.Model.CommandSpec mixin = mixins.get(mixinName);
            ClassName mixinType = cmdClassName((Symbol) mixin.userObject());
            String varName = mixinName + "Spec";
            cfgMth.addStatement("obj.$L = new $T()", mixinName, mixinType)
                    .addStatement("$T $N = $T.wrapWithoutInspection(obj.$L)", CommandLine.Model.CommandSpec.class, varName, CommandLine.Model.CommandSpec.class, mixinName)
                    .addStatement("cs.addMixin($S, $N)", mixinName, varName);
        }
    }

    private void addUnmatched(MethodSpec.Builder cfgMth, CommandLine.Model.UnmatchedArgsBinding unmatched) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "UNMATCHED NYI " + unmatched);
        //TODO Handle @Unmatched
    }

    private <T extends CommandLine.Model.ArgSpec> Collection<T> directArgs(Collection<T> args, Symbol ownerSym) {
        return args.stream().filter(arg -> {
            Symbol argSym = (Symbol) arg.userObject();
            // Make sure the option is a direct member of the class
            return !arg.inherited() && argSym.owner.equals(ownerSym);
        }).collect(Collectors.toList());
    }

    private void addArgs(MethodSpec.Builder cfgMth, Collection<CommandLine.Model.ArgSpec> args) {
        for (CommandLine.Model.ArgSpec arg : args) {
            if (arg instanceof CommandLine.Model.OptionSpec) {
                addOption(cfgMth, (CommandLine.Model.OptionSpec) arg);
            } else if (arg instanceof CommandLine.Model.PositionalParamSpec) {
                addParameter(cfgMth, (CommandLine.Model.PositionalParamSpec) arg);
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Unsupported ArgSpec type: " + arg.getClass().getName());
            }
        }
    }

    private void configureParameters(TypeSpec.Builder cmdClass, Symbol sym, Collection<CommandLine.Model.PositionalParamSpec> params) {
        String mthName = getConfigureParametersName(sym);
        MethodSpec.Builder cfgMth = configConsumerMethod(sym, mthName, CommandLine.Model.PositionalParamSpec.class);
        addParameters(cfgMth, params);
        cmdClass.addMethod(cfgMth.build());
    }

    private void addParameters(MethodSpec.Builder cfgMth, Collection<CommandLine.Model.PositionalParamSpec> params) {
        for (CommandLine.Model.PositionalParamSpec param : params) {
            addParameter(cfgMth, param);
        }
    }

    private void addParameter(MethodSpec.Builder cfgMth, CommandLine.Model.PositionalParamSpec param) {
        Symbol paramSym = (Symbol) param.userObject();
        String varName = paramSym.name + "Param";

        cfgMth
                .addStatement("$T $N = $T.builder()", CommandLine.Model.PositionalParamSpec.Builder.class, varName, CommandLine.Model.PositionalParamSpec.class)
                .addStatement("$N.index($S)", varName, param.index().toString());

        configureArgSpec(cfgMth, param, varName);

        cfgMth.addStatement("cons.accept($N.build())", varName);
    }

    private void configureOptions(TypeSpec.Builder cmdClass, Symbol sym, Collection<CommandLine.Model.OptionSpec> options) {
        String mthName = getConfigureOptionsName(sym);
        MethodSpec.Builder cfgMth = configConsumerMethod(sym, mthName, CommandLine.Model.OptionSpec.class);
        addOptions(cfgMth, options);
        cmdClass.addMethod(cfgMth.build());
    }

    private void addOptions(MethodSpec.Builder cfgMth, Collection<CommandLine.Model.OptionSpec> options) {
        for (CommandLine.Model.OptionSpec option : options) {
            addOption(cfgMth, option);
        }
    }

    private void addOption(MethodSpec.Builder cfgMth, CommandLine.Model.OptionSpec opt) {
        Symbol optSym = (Symbol) opt.userObject();
        String varName = optSym.name + "Opt";

        cfgMth
                .addCode("$T $N = $T.builder", CommandLine.Model.OptionSpec.Builder.class, varName, CommandLine.Model.OptionSpec.class)
                .addCode("(" + stringsArg(opt.names()) + ");\n", (Object[]) opt.names())
                .addStatement("$N.fallbackValue($S)", varName, opt.fallbackValue())
                .addStatement("$N.negatable($L)", varName, opt.negatable())
                .addStatement("$N.usageHelp($L)", varName, opt.usageHelp());

        configureArgSpec(cfgMth, opt, varName);

        if (opt.preprocessor() != null && !opt.preprocessor().getClass().getName().contains("NoOpParameterPreprocessor")) {
            cfgMth.addStatement("$N.preprocessor($T)", varName, opt.preprocessor().getClass());
        }
        if (opt.parameterConsumer() != null) {
//            ParameterConsumerMetaData cons = opt.parameterConsumer();
//            cfgOptsMth.addStatement("$N.parameterConsumer($T)", optName, opt.parameterConsumer().getClass());
        }
        cfgMth.addStatement("cons.accept($N.build())", varName);
    }

    private MethodSpec.Builder getterMethod(Symbol optSym) {
        String type = optSym.type.tsym.name.toString();
        String cast = "";
        if (optSym.type.isPrimitive()) {
            if (optSym.type.getKind() == TypeKind.INT) {
                cast = "(Integer)";
            } else {
                cast = "(" + type.substring(0, 1).toUpperCase() + type.substring(1) + ")";
            }
        }

        MethodSpec.Builder mth = MethodSpec.methodBuilder("get")
                .addAnnotation(Override.class)
                .addTypeVariable(TypeVariableName.get("T"))
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeVariableName.get("T"));

        mth.addCode("@SuppressWarnings(\"unchecked\") T result = ");
        if (optSym instanceof Symbol.VarSymbol) {
            mth.addCode("(T)$L", cast + "obj." + optSym.name.toString() + ";\n");
        } else if (optSym instanceof Symbol.MethodSymbol) {
            mth.addCode("(T)$L", cast + "obj." + optSym.name.toString() + "();\n");
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Unsupported symbol type: " + optSym.kind);
        }
        mth.addStatement("return result");

        return mth;
    }

    private MethodSpec.Builder setterMethod(Symbol optSym) {
        MethodSpec.Builder mth = MethodSpec.methodBuilder("set")
                .addAnnotation(Override.class)
                .addTypeVariable(TypeVariableName.get("T"))
                .addModifiers(Modifier.PUBLIC)
                .addParameter(TypeVariableName.get("T"), "value")
                .returns(TypeVariableName.get("T"));

        mth.addStatement("T $N = value", "oldValue");
        mth.addCode("@SuppressWarnings(\"unchecked\") ");
        if (optSym instanceof Symbol.VarSymbol) {
            mth.addCode("$T newValue = ($T) value;\n", optSym.type, optSym.type);
            mth.addStatement("obj.$L = newValue", optSym.name.toString());
        } else if (optSym instanceof Symbol.MethodSymbol) {
            Type type = ((Symbol.MethodSymbol) optSym).getParameters().get(0).asType();
            mth.addCode("$T newValue = ($T) value;\n", type, type);
            mth.addStatement("obj.$L(newValue)", optSym.name.toString());
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Unsupported symbol type: " + optSym.kind);
        }
        mth.addStatement("return oldValue");

        return mth;
    }

    private void configureArgSpec(MethodSpec.Builder cfgMth, CommandLine.Model.ArgSpec arg, String varName) {
        cfgMth
                .addStatement("$N.arity($S)", varName, arg.arity().toString())
                .addCode("$N.description", varName)
                .addCode("(" + stringsArg(arg.description()) + ");\n", (Object[]) arg.description())
                .addStatement("$N.hasInitialValue($L)", varName, arg.hasInitialValue())
                .addStatement("$N.hidden($L)", varName, arg.hidden())
                .addStatement("$N.scopeType($T.$L)", varName, CommandLine.ScopeType.class, arg.scopeType())
                .addStatement("$N.type($L.class)", varName, arg.typeInfo().getClassName());
        if (!arg.typeInfo().getAuxiliaryTypeInfos().isEmpty()) {
            String params = multiArg("$L", arg.typeInfo().getAuxiliaryTypeInfos().size());
            String[] args = arg.typeInfo().getAuxiliaryTypeInfos().stream().map(aux -> aux.getClassName() + ".class").toArray(String[]::new);
            cfgMth.addCode("$N.auxiliaryTypes", varName);
            cfgMth.addCode("(" + params + ");\n", (Object[]) args);
        }
        if (arg.defaultValue() != null) {
            cfgMth.addStatement("$N.defaultValue($S)", varName, arg.defaultValue());
        }
        if (!"__unspecified__".equals(arg.mapFallbackValue())) {
            cfgMth.addStatement("$N.mapFallbackValue($S)", varName, arg.mapFallbackValue());
        }
        if (arg.parameterConsumer() != null) {
            //HACK Dirty hack to get access to the wrapped Consumer
            String wrappedClassName = arg.parameterConsumer().toString();
            wrappedClassName = wrappedClassName.substring(26, wrappedClassName.length() - 1);
            cfgMth.addStatement("$N.parameterConsumer(new $T())", varName, ClassName.bestGuess(wrappedClassName));
        }

        Symbol argSym = (Symbol) arg.userObject();
        if (argSym.owner instanceof Symbol.ClassSymbol) {
            if (argSym instanceof Symbol.VarSymbol) {
                TypeName igetterName = ClassName.get(CommandLine.Model.IGetter.class);
                cfgMth
                        .addStatement("$N.getter($L)", varName,
                                TypeSpec.anonymousClassBuilder("")
                                        .addSuperinterface(igetterName)
                                        .addMethod(getterMethod(argSym).build())
                                        .build());
            }

            TypeName isetterName = ClassName.get(CommandLine.Model.ISetter.class);
            cfgMth
                    .addStatement("$N.setter($L)", varName,
                            TypeSpec.anonymousClassBuilder("")
                                    .addSuperinterface(isetterName)
                                    .addMethod(setterMethod(argSym)
                                            .build())
                                    .build());
        }
    }

    private MethodSpec.Builder configMethod(Symbol sym, String mthName) {
        // GEN public static void configureXxxx(Consumer<Xxxx> addElem, Zzzz obj)
        TypeName cmdSpecType = ClassName.get(CommandLine.Model.CommandSpec.class);
        ClassName cmdClassName = cmdClassName(sym);
        return MethodSpec.methodBuilder(mthName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(cmdSpecType, "cs")
                .addParameter(cmdClassName, "obj");
    }

    private MethodSpec.Builder configConsumerMethod(Symbol sym, String mthName, Class consElemType) {
        // GEN public static void configureXxxx(Consumer<Xxxx> addElem, Zzzz obj)
        TypeName consumerType = ParameterizedTypeName.get(Consumer.class, consElemType);
        ClassName cmdClassName = cmdClassName(sym);
        return MethodSpec.methodBuilder(mthName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(consumerType, "cons")
                .addParameter(cmdClassName, "obj");
    }

    private Symbol.ClassSymbol cmdClass(Symbol cmdSym) {
        if (cmdSym instanceof Symbol.ClassSymbol) {
            return (Symbol.ClassSymbol) cmdSym;
        } else if (cmdSym instanceof Symbol.MethodSymbol) {
            Symbol.MethodSymbol mth = (Symbol.MethodSymbol) cmdSym;
            //TODO This assumes methods are always contained within a class!
            return (Symbol.ClassSymbol) mth.owner;
        } else {
            return null;
        }
    }

    private ClassName cmdClassName(Symbol cmdSym) {
        Symbol.ClassSymbol clz = cmdClass(cmdSym);
        return clz != null ? ClassName.get(clz) : null;
    }

    private boolean isCommand(Symbol cmdSym) {
        CommandLine.Command cmdAnno = cmdSym.getAnnotation(CommandLine.Command.class);
        return cmdAnno != null;
    }

    private ClassName factClassName(Symbol cmdSym) {
        String postfix = "_CliCfg";
        ClassName cmdName = cmdClassName(cmdSym);
        String[] names = cmdName.simpleNames().stream().map(nm -> nm + postfix).toArray(String[]::new);
        return ClassName.get(cmdName.packageName(), names[0], Arrays.copyOfRange(names, 1, names.length));
    }

    private String getConfigureAllName(Symbol cmdSym) {
        return factMethodName(cmdSym, "configureAll");
    }

    private String getConfigureUnmatchedsName(Symbol cmdSym) {
        return factMethodName(cmdSym, "configureUnmatcheds");
    }

    private String getConfigureParentCommandsName(Symbol cmdSym) {
        return factMethodName(cmdSym, "configureParentCommands");
    }

    private String getConfigureSpecsName(Symbol cmdSym) {
        return factMethodName(cmdSym, "configureSpecs");
    }

    private String getConfigureArgGroupsName(Symbol cmdSym) {
        return factMethodName(cmdSym, "configureArgGroups");
    }

    private String getConfigureMixinsName(Symbol cmdSym) {
        return factMethodName(cmdSym, "configureMixins");
    }

    private String getConfigureParametersName(Symbol cmdSym) {
        return factMethodName(cmdSym, "configureParameters");
    }

    private String getConfigureOptionsName(Symbol cmdSym) {
        return factMethodName(cmdSym, "configureOptions");
    }

    private void addConfigureCall(MethodSpec.Builder cfgMth, Symbol sym, String methodName, String consumerMethod, String varName) {
        if (sym != null) {
            cfgMth.addStatement("$T.$L($L, $L)", factClassName(sym), methodName, consumerMethod, varName);
        } else {
            cfgMth.addStatement("$L($L, $L)", methodName, consumerMethod, varName);
        }
    }

    private String getCreateCmdSpecName(Symbol cmdSym) {
        return factMethodName(cmdSym, "createCmdSpec");
    }

    private String factMethodName(Symbol cmdSym, String name) {
        if (cmdSym instanceof Symbol.MethodSymbol) {
            name += "_" + cmdSym.name;
        }
        return name;
    }

    private Modifier[] modifiers(Symbol sym) {
        return sym.getModifiers().toArray(new Modifier[] {});
    }

    private String stringsArg(String[] strings) {
        return Stream.generate(() -> "$S").limit(strings.length).collect(Collectors.joining(","));
    }

    private String multiArg(String sym, int count) {
        return Stream.generate(() -> sym).limit(count).collect(Collectors.joining(","));
    }

    private Symbol getSuperClass(Symbol cmdSym, Collection<CommandLine.Model.CommandSpec> specs) {
        if (cmdSym instanceof Symbol.ClassSymbol) {
            Symbol superClz = ((Symbol.ClassSymbol) cmdSym).getSuperclass().tsym;
            Optional<Symbol> superClassSym = specs.stream()
                    .map(spec -> (Symbol) spec.userObject())
                    .filter(obj -> obj.equals(superClz))
                    .findAny();
            if (superClassSym.isPresent()) {
                return superClassSym.get();
            } else {
                return getSuperClass(superClz, specs);
            }
        }
        return null;
    }

    // Returns the set of symbols ordered by decreasing
    // nesting depth (so top level classes appear at the
    // back of the list).
    private List<Symbol> depthSort(Set<Symbol> syms) {
        Set<Symbol> unsortedSyms = new LinkedHashSet<>(syms);
        Set<Symbol> sortedSyms = new LinkedHashSet<>();
        while (!unsortedSyms.isEmpty()) {
            depthSort(sortedSyms, unsortedSyms);
        }
        List<Symbol> result = new ArrayList<>(sortedSyms);
        Collections.reverse(result);
        return result;
    }

    private void depthSort(Set<Symbol> result, Set<Symbol> syms) {
        syms.stream()
                .filter(sym -> sym.owner instanceof Symbol.PackageSymbol || syms.contains(sym.owner))
                .collect(Collectors.toList())
                .forEach(sym -> {
                        syms.remove(sym);
                        result.add(sym);
                });
    }
}
