package dev.jbang.source;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.jbang.catalog.Alias;
import dev.jbang.cli.BaseCommand;
import dev.jbang.cli.ExitException;
import dev.jbang.dependencies.ModularClassPath;

/**
 * A Source is an interface for classes representing different inputs (sources)
 * that can be used as or turned into executable code.
 */
public interface Source {
	String ATTR_BUILD_JDK = "Build-Jdk";
	String ATTR_JBANG_JAVA_OPTIONS = "JBang-Java-Options";
	String ATTR_BOOT_CLASS_PATH = "Boot-Class-Path";
	String ATTR_PREMAIN_CLASS = "Premain-Class";
	String ATTR_AGENT_CLASS = "Agent-Class";

	/**
	 * Returns the reference to resource to be executed. This contains both the
	 * original reference (which can be a URL or Maven GAV or other kinds of
	 * non-file resource references) and a path to the file that contains the actual
	 * resource (which can be a ephemeral temporary/cached file).
	 */
	ResourceRef getResourceRef();

	/**
	 * Returns the path to the main application JAR file. This can be an existing
	 * JAR file or one that was generated by Jbang.
	 */
	File getJarFile();

	/**
	 * Returns the main class of the application JAR file or `null` if this can't be
	 * determined.
	 */
	default String getMainClass() {
		return null;
	}

	// https://stackoverflow.com/questions/366202/regex-for-splitting-a-string-using-space-when-not-surrounded-by-single-or-double
	static List<String> quotedStringToList(String subjectString) {
		List<String> matchList = new ArrayList<>();
		Pattern regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
		Matcher regexMatcher = regex.matcher(subjectString);
		while (regexMatcher.find()) {
			if (regexMatcher.group(1) != null) {
				// Add double-quoted string without the quotes
				matchList.add(regexMatcher.group(1));
			} else if (regexMatcher.group(2) != null) {
				// Add single-quoted string without the quotes
				matchList.add(regexMatcher.group(2));
			} else {
				// Add unquoted word
				matchList.add(regexMatcher.group());
			}
		}
		return matchList;
	}

	/**
	 * Returns the runtime Java options that should be passed to the `java`
	 * executable when the application gets run.
	 */
	default List<String> getRuntimeOptions() {
		return Collections.emptyList();
	}

	/**
	 * Determines if CDS has been enabled for this Source
	 */
	default boolean enableCDS() {
		return false;
	}

	/**
	 * Returns the requested Java version
	 */
	String getJavaVersion();

	/**
	 * Returns the resource's description. Returns `Optional.empty()` if no
	 * description is available (or if the description is an empty string).
	 */
	default Optional<String> getDescription() {
		return Optional.empty();
	}

	/**
	 * Returns the list of dependencies that are necessary to add to the classpath
	 * for the application to execute properly.
	 * 
	 * @param props A `Properties` object whose values can be used during dependency
	 *              resolution
	 */
	List<String> getAllDependencies(Properties props);

	/**
	 * Resolves the given list of dependencies
	 *
	 * @param dependencies List of dependencies
	 */
	ModularClassPath resolveClassPath(List<String> dependencies);

	default boolean isJar() {
		return Source.isJar(getResourceRef().getFile());
	}

	static boolean isJar(File backingFile) {
		return backingFile != null && backingFile.toString().endsWith(".jar");
	}

	default boolean isJShell() {
		return Source.isJShell(getResourceRef().getFile());
	}

	static boolean isJShell(File backingFile) {
		return backingFile != null && backingFile.toString().endsWith(".jsh");
	}

	/**
	 * Returns the JarSource associated with this Source or `null` if there is none
	 * or if it's invalid (invalid could mean that the associated jar is out-of-date
	 * and needs to be rebuilt).
	 */
	JarSource asJarSource();

	static Source forResource(String resource, RunContext ctx) {
		ResourceRef resourceRef = ResourceRef.forScriptResource(resource);

		Alias alias = null;
		if (resourceRef == null) {
			// Not found as such, so let's check the aliases
			alias = Alias.get(resource, ctx.getArguments(), ctx.getProperties());
			if (alias != null) {
				resourceRef = ResourceRef.forResource(alias.resolve());
				ctx.setArguments(alias.arguments);
				ctx.setProperties(alias.properties);
				ctx.setAlias(alias);
				if (resourceRef == null) {
					throw new IllegalArgumentException(
							"Alias " + resource + " from " + alias.catalog.catalogRef + " failed to resolve "
									+ alias.scriptRef);
				}
			}
		}

		// Support URLs as script files
		// just proceed if the script file is a regular file at this point
		if (resourceRef == null || !resourceRef.getFile().canRead()) {
			throw new ExitException(BaseCommand.EXIT_INVALID_INPUT,
					"Script or alias could not be found or read: '" + resource + "'");
		}

		// note script file must be not null at this point
		ctx.setOriginalRef(resource);
		return forResourceRef(resourceRef);
	}

	static Source forFile(File resourceFile) {
		ResourceRef resourceRef = ResourceRef.forFile(resourceFile);
		return forResourceRef(resourceRef);
	}

	static Source forResourceRef(ResourceRef resourceRef) {
		Source src;
		if (resourceRef.getFile().getName().endsWith(".jar")) {
			src = JarSource.prepareJar(resourceRef);
		} else {
			src = ScriptSource.prepareScript(resourceRef);
		}
		return src;
	}

	static ScriptSource forScript(String script) {
		return new ScriptSource(script);
	}

	boolean isCreatedJar();
}
