/*
 * Copyright 2008 Sun Microsystems, Inc. All rights reserved.
 * 
 * U.S. Government Rights - Commercial software. Government users 
 * are subject to the Sun Microsystems, Inc. standard license agreement
 * and applicable provisions of the FAR and its supplements.
 * 
 * Use is subject to license terms.
 * 
 * This distribution may include materials developed by third parties.
 * Sun, Sun Microsystems, the Sun logo, Java and Project Identity 
 * Connectors are trademarks or registered trademarks of Sun 
 * Microsystems, Inc. or its subsidiaries in the U.S. and other
 * countries.
 * 
 * UNIX is a registered trademark in the U.S. and other countries,
 * exclusively licensed through X/Open Company, Ltd. 
 * 
 * -----------
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2008 Sun Microsystems, Inc. All rights reserved. 
 * 
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License(CDDL) (the License).  You may not use this file
 * except in  compliance with the License. 
 * 
 * You can obtain a copy of the License at
 * http://identityconnectors.dev.java.net/CDDLv1.0.html
 * See the License for the specific language governing permissions and 
 * limitations under the License.  
 * 
 * When distributing the Covered Code, include this CDDL Header Notice in each
 * file and include the License file at identityconnectors/legal/license.txt.
 * If applicable, add the following below this CDDL Header, with the fields 
 * enclosed by brackets [] replaced by your own identifying information: 
 * "Portions Copyrighted [year] [name of copyright owner]"
 * -----------
 */

package org.identityconnectors.contract.data;

import groovy.util.ConfigObject;
import groovy.util.ConfigSlurper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.identityconnectors.common.CollectionUtil;
import org.identityconnectors.common.IOUtil;
import org.identityconnectors.common.StringUtil;
import org.identityconnectors.common.logging.Log;
import org.identityconnectors.common.security.GuardedString;
import org.identityconnectors.contract.data.groovy.Get;
import org.identityconnectors.contract.data.groovy.Lazy;
import org.identityconnectors.contract.data.groovy.Random;
import org.identityconnectors.contract.data.macro.Macro;
import org.identityconnectors.contract.exceptions.ObjectNotFoundException;
import org.identityconnectors.framework.test.TestHelpers;
import org.junit.Assert;

/**
 * <p>
 * Default implementation of {@link DataProvider}. It uses ConfigSlurper from
 * Groovy to parse the property file.
 * </p>
 * <p>
 * Order of lookup for the property files follows:
 * </p>
 * <ol>
 * <li>bundle-dir/contract-tests.groovy</li>
 * <li>USER_HOME/.bundle-name.groovy</li>
 * <li>USER_HOME/.bundle-name/contract-tests.groovy</li>
 * </ol>
 * <p>
 * Note: If two property files contain the same property name, the value from
 * the latter file the list <b>overrides</b> the others. I.e. the last file
 * from the list has the greatest chance to propagate its values to the final
 * configuration.
 * </p>
 * 
 * <p>
 * <code>Lazy.random("####")</code> is used for generating random strings, in
 * case numeric object is needed, use for instance
 * <code>Lazy.random("####", Long.class)</code> to get a Long object with
 * random value.
 * </p>
 * 
 * <p>
 * Type names are now prefixed with T, so use Tstring instead of string (as in
 * DefaultDataProvider)
 * </p>
 * 
 * @author David Adam, Zdenek Louzensky
 */
public class GroovyDataProvider implements DataProvider {

	private static final String PROPERTY_SEPARATOR = ".";
	/** just for test purposes see GroovyDataProviderTest */
	public static final String CONFIG_FILE_PATH = "src//configfile.groovy";

	/** holds the parsed config file */
	private ConfigObject configObject;

	/** cache for retrieved values */
	private Map<String, Object> cache = new HashMap<String, Object>();

	/** contains property name that user asked last time */
	private String currentQuery = null; // TODO try to eliminate this var. to
										// make it thread safe

	private static/*
					 * TODO delete static, it's just here because of for Unit
					 * tests.
					 */final ConfigSlurper cs = new ConfigSlurper();

	private static final Log LOG = Log.getLog(GroovyDataProvider.class);

	/* *********************** */
	private static final String PARAM_PROPERTY_FILE = "defaultdataprovider.propertyFile";
	private static final String PARAM_PROPERTY_OUT_FILE = "test.parameters.outFile";
	private static final String GLOBAL_PROPERTY_FILE = "dataprovider-global.properties";

	// private File _propertyOutFile = null; // TODO implement output to
	// property files, as was in DefaultDataProvider.

	/**
	 * default constructor
	 */
	public GroovyDataProvider() {
		configObject = doBootstrap();

		ConfigObject configObjectNew = loadProjectProperties();

		ConfigObject tmp = mergeConfigObjects(configObject, configObjectNew);
		configObject = tmp;
	}

	/**
	 * Constructor for JUnit Testing purposes only. Do not use it normally.
	 */
	public GroovyDataProvider(String nullStr, String nullStr2, String null3) {

		doBootstrap();

		File f = new File(CONFIG_FILE_PATH);

		try {
			// parse the configuration file once
			configObject = cs.parse(f.toURL());
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
	}

	/** load the bootstrap configuration */
	private ConfigObject doBootstrap() {
		final String BOOTSTRAP_LOCATION = "bootstrap.groovy";

		URL url = getClass().getClassLoader().getResource(BOOTSTRAP_LOCATION);

		return cs.parse(url);
	}

	/**
	 * load properties in the following order:
	 * <ul>
	 * <li>1) bundle-dir/contract-tests.groovy
	 * <li>2) user-home/.bundle-name/contract-tests.groovy
	 * <li>3) user-home/.bundle-name/contract-tests-configuration.groovy --
	 * this is of the highest priority
	 * </ul>
	 * 
	 */
	private static ConfigObject loadProjectProperties() {
		// CONTANTS
		final String CONTRACT_TESTS_MARKER = "contract-tests";
		final String CONTRACT_TEST_EXTENSION = ".groovy";
		final String LOCALCONFIG_NAME = CONTRACT_TESTS_MARKER
				+ CONTRACT_TEST_EXTENSION;

		/**
		 * main config object, that will contain the merged result form 3
		 * configuration files.
		 */
		ConfigObject co = null;

		/*
		 * load the local properties file #1
		 */
		if (new File(LOCALCONFIG_NAME).exists()) {
			co = parsePropertyFile(LOCALCONFIG_NAME);
		}// #1

		String prjName = System.getProperty("project.name");
		File userHome = new File(System.getProperty("user.home"));

		if (StringUtil.isNotBlank(prjName)) {
			/*
			 * load the user properties file (project specific) #2 including
			 * CONFIGURATION SPECIFIC files #3
			 */
			// includes the parent configuration and the specific config
			List<String> configurations = null;
			{
				configurations = CollectionUtil.newList(CONTRACT_TESTS_MARKER);
				// determine the configuration property
				String cfg = System.getProperty("configuration", null);
				if (StringUtil.isNotBlank(cfg)) {
					String name = CONTRACT_TESTS_MARKER + "-" + cfg;
					configurations.add(name);
				}

				String prjFolderName = String.format(".%s", prjName);
				File f = new File(userHome, prjFolderName);

				for (String configFile : configurations) {
					// read the config file's contents and merge it:
					String fn = String.format("%s" + CONTRACT_TEST_EXTENSION,
							configFile);
					File cnfg = new File(f, fn);
					if (cnfg.exists()) {
						ConfigObject lowPriorityCObj = parsePropertyFile(cnfg
								.getAbsolutePath());
						if (co != null) {
							ConfigObject merged = mergeConfigObjects(co,
									lowPriorityCObj);
							co = merged; // co holds the final ConfigObject
						} else {
							co = lowPriorityCObj;
						}
					}
				}
			}// configuration init

		}

		// load the system properties
		// ret.putAll(System.getProperties()); TODO do we add system properties
		// automatically?

		String s = co.getProperty("password.connector.string").toString();
		return co;
	}

	public static ConfigObject mergeConfigObjects(ConfigObject lowPriorityCO,
			ConfigObject highPriorityCO) {
		return (ConfigObject) lowPriorityCO.merge(highPriorityCO);
	}

	/**
	 * parse the groovy config file
	 * 
	 * @param path
	 * @return
	 */
	public static ConfigObject parsePropertyFile(String path) {
		ConfigObject result = null;

		File f = new File(path);

		try {
			// parse the configuration file once
			URL url = f.toURL();
			result = cs.parse(url);
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}

		return result;
	}

	/**
	 * TODO javadoc
	 * 
	 * @param name
	 *            property name
	 * @return the value for given property
	 * @throws Exception
	 */
	public Object get(String name) throws ObjectNotFoundException {
		currentQuery = name; // save the original query (will be used later)
		currentQuery = currentQuery.replace('@', '_');
		// when caching... see get())

		Object o = recursiveGet(currentQuery);
		// resolve o.n.f.e.
		if (o instanceof ObjectNotFoundException) {
			throw (ObjectNotFoundException) o;
		}
		return o;
	}

	/**
	 * try to resolve the property's value
	 * 
	 * @param name
	 * @return
	 */
	private Object recursiveGet(String name) {
		Object response = null;

		try {

			// get the property for given name
			// (in case property is not found, ObjectNotFoundException will be
			// thrown.)
			response = get(name, this.configObject);

		} catch (ObjectNotFoundException onfe) {
			// we did not found the property for given name, try to search it
			// recursively
			// by deleting the first prefix
			int separatorIndex = name.indexOf(PROPERTY_SEPARATOR, 0);

			if (separatorIndex != -1) {
				separatorIndex++;
				if (separatorIndex < name.length()) {
					return recursiveGet(name.substring(separatorIndex));
				}
			} else {
				throw new ObjectNotFoundException(
						"Can't find object for key:  " + this.currentQuery);
			}// fi
		}// catch

		return response;
	}

	/**
	 * contains key functionality for acquiring properties with hierarchical
	 * names (e.g. foo.bar.spam)
	 * 
	 * @param name
	 *            property name
	 * @param co
	 *            configuration model, that contains all the property key/value
	 *            pairs
	 * @return
	 * @throws ObjectNotFoundException
	 */
	private Object get(String name, ConfigObject co)
			throws ObjectNotFoundException {
		int dotIndex = name.indexOf(PROPERTY_SEPARATOR);
		if (dotIndex >= 0) {
			String currentNamePart = name.substring(0, dotIndex);

			/*
			 * request the property name from parsed config file
			 */
			Object o = callGet(co, currentNamePart);

			if (o instanceof ConfigObject) {
				// recursively resolve the hierarchical names (containing
				// multiple dots.
				return get(name.substring(dotIndex + 1), (ConfigObject) o);
			} else {

				Assert.fail("It should not get here. Unexpected instance: "
						+ o.getClass().getName());
				return null;
			}// fi inner

		} else {

			/*
			 * request the property name from parsed config file
			 */
			return callGet(co, name);
		}// fi outer
	}

	/**
	 * 
	 * @param co
	 *            current config object which is queried
	 * @param currentNamePart
	 *            the queried property name
	 * @return the value for given property name
	 * @throws ObjectNotFoundException
	 */
	private Object callGet(ConfigObject co, String currentNamePart)
			throws ObjectNotFoundException {

		/*
		 * get the property value
		 */
		Object result = co.getProperty(currentNamePart);

		if (result instanceof ConfigObject) {
			// try if property value is empty
			ConfigObject coResult = (ConfigObject) result;
			if (coResult.size() == 0) {
				throw new ObjectNotFoundException();
			}
		} else {

			result = resolvePropObject(result);

		}// fi
		return result;
	}

	/**
	 * resolve the special types TODO javadoc
	 * 
	 * @param o
	 * @return
	 */
	private Object resolvePropObject(Object o) {
		/** holds the queried string */
		Object tmpResult = o;

		if (!cache.containsKey(currentQuery)) {
			// cache the queried parameter for the first time
			if (o instanceof Lazy) {
				Lazy lazy = (Lazy) o;

				tmpResult = resolveLazy(lazy);
			}

			cache.put(currentQuery, tmpResult);
		}

		// return cached property
		return cache.get(currentQuery);
	}

	private Object resolveLazy(Lazy lazy) {
		Object value = lazy.getValue();
		Object resolvedValue = null;
		if (value != null) {
			if (value instanceof Lazy) {
				value = resolveLazy((Lazy) value);
			}
			if (lazy instanceof Get) {
				Assert.assertTrue(value instanceof String);

				if (cache.containsKey(value)) {
					resolvedValue = cache.get(value);
				} else {
					resolvedValue = get((String) value, this.configObject);
				}

			} else if (lazy instanceof Random) {
				Assert.assertTrue(value instanceof String);
				Random rnd = (Random) lazy;
				resolvedValue = rnd.generate();// RandomGenerator.generate((String)
												// value);
			}
		}

		if (!lazy.getSuccessors().isEmpty()) {
			return concatenate(resolvedValue, lazy.getSuccessors());
		} else {
			return resolvedValue;
		}
	}

	private String concatenate(Object value, List<Object> successors) {
		StringBuffer sb = new StringBuffer();
		if (value != null) {
			sb.append(value.toString());
		}
		for (Object o : successors) {
			if (o instanceof String) {
				sb.append((String) o);
			} else if (o instanceof Lazy) {
				Object resolved = resolveLazy((Lazy) o);
				sb.append(resolved.toString());
			}
		}

		return sb.toString();
	}

	/* ************************ interface DataProvider ********************** */

	@Override
	public Object get(String dataTypeName, String name, String componentName,
			int sequenceNumber) throws ObjectNotFoundException {
		// put the parameters in the Map ... this will fail if called
		// recursively

		String shortTypeName = getShortTypeName(dataTypeName);

		StringBuffer sbPath = new StringBuffer();
		if (sequenceNumber != -1) {
			sbPath.append(sequenceNumber);
			sbPath.append(".");
		}

		sbPath.append(componentName);
		sbPath.append(".");
		sbPath.append(name);
		sbPath.append(".");
		sbPath.append(shortTypeName);
		LOG.info("getting data for ''{0}''", sbPath);

		try {

			// call get to resolve the property value
			Object obj = get(sbPath.toString());

			LOG.info("Fully resolved ''{0}'' to value ''{1}''", sbPath
					.toString(), obj);
			return obj;

		} catch (ObjectNotFoundException ex) {
			LOG.info(ex, "Unable to find data for ''{0}''", sbPath.toString());
			throw ex;
		} catch (Exception ex) {
			LOG.error(ex, "Error occured expanding macro");
		}
		// found nothing, so return nothing
		throw new ObjectNotFoundException("Can't find object for key:  " + name);

	}

	/**
	 * {@inheritDoc}
	 */
	public Object get(String dataTypeName, String name, String componentName)
			throws ObjectNotFoundException {

		return get(dataTypeName, name, componentName, -1);
	}

	/**
	 * {@inheritDoc}
	 */
	public String getString(String name, String componentName,
			int sequenceNumber) throws ObjectNotFoundException {
		return (String) get(String.class.getName(), name, componentName,
				sequenceNumber);
	}

	/**
	 * {@inheritDoc}
	 */
	public String getString(String name, String componentName)
			throws ObjectNotFoundException {
		return (String) get(String.class.getName(), name, componentName);
	}

	/**
	 * {@inheritDoc}
	 */
	public Object getTestSuiteAttribute(String typeName, String propName)
			throws ObjectNotFoundException {

		return get(propName + "." + "testsuite." + getShortTypeName(typeName));
	}

	/**
	 * {@inheritDoc}
	 */
	public Object getConnectorAttribute(String typeName, String propName)
			throws ObjectNotFoundException {

		return get(propName + "." + "connector." + getShortTypeName(typeName));
	}

	/* ************** AUXILIARY METHODS *********************** */
	/**
	 * gets short name for the type, eg. java.lang.String returns string
	 * 
	 * @param Type
	 *            Name
	 * @return Short Name
	 */
	private String getShortTypeName(String typeName) {
		String shortName = typeName;

		if (typeName.equals(GuardedString.class.getName())) {
			shortName = "string";
		} else if (typeName.equals("[B")) {
			shortName = "bytearray";
		} else if (typeName.equals("[Ljava.lang.String;")
				|| typeName
						.equals("[Lorg.identityconnectors.common.security.GuardedString;")) {
			shortName = "stringarray";
		} else if (typeName.equals("[Ljava.lang.Long;")
				|| typeName.equals("[J")) {
			shortName = "longarray";
		} else if (typeName.equals("[Ljava.lang.Integer;")
				|| typeName.equals("[I")) {
			shortName = "integerarray";
		} else if (typeName.equals("[Ljava.lang.Double;")
				|| typeName.equals("[D")) {
			shortName = "doublearray";
		} else if (typeName.equals("[Ljava.lang.Float;")
				|| typeName.equals("[F")) {
			shortName = "floatarray";
		} else if (typeName.equals("[Ljava.lang.Boolean;")
				|| typeName.equals("[Z")) {
			shortName = "floatarray";
		} else if (typeName.equals("[Ljava.net.URI;")) {
			shortName = "uriarray";
		} else if (typeName.equals("[Ljava.io.File;")) {
			shortName = "filearray";
		} else {
			int lindex = typeName.lastIndexOf(".");
			if (lindex != -1) {
				shortName = typeName.substring(lindex + 1);
				shortName = shortName.toLowerCase();
			} else {
				LOG.warn("Can't get short type for ''{0}''", typeName);
			}
		}

		return "T" + shortName;
	}

}