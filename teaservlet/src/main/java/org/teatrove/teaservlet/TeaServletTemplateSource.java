/*
 *  Copyright 1997-2011 teatrove.org
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.teatrove.teaservlet;

import org.teatrove.tea.compiler.CompilationUnit;
import org.teatrove.tea.compiler.ErrorEvent;
import org.teatrove.tea.compiler.TemplateRepository;
import org.teatrove.tea.compiler.TemplateRepository.TemplateInfo;
import org.teatrove.tea.engine.ContextSource;
import org.teatrove.tea.engine.ReloadLock;
import org.teatrove.tea.engine.TemplateCompilationResults;
import org.teatrove.tea.engine.TemplateError;
import org.teatrove.tea.engine.TemplateErrorListener;
import org.teatrove.tea.engine.TemplateSource;
import org.teatrove.tea.engine.TemplateSourceConfig;
import org.teatrove.tea.engine.TemplateSourceImpl;
import org.teatrove.teaservlet.util.RemoteCompiler;
import org.teatrove.trove.log.Log;
import org.teatrove.trove.util.ClassInjector;
import org.teatrove.trove.util.PropertyMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.Vector;

/**
 *
 * @author Jonathan Colwell
 */
public class TeaServletTemplateSource extends TemplateSourceImpl {

    /** Package for templates */
    public final static String TEMPLATE_PACKAGE = "org.teatrove.teaservlet.template";
    /** Short package for system templates */
    public final static String SYSTEM_PACKAGE = "system";
    /** Full package for system templates */
    public final static String SYSTEM_TEMPLATE_PACKAGE = TEMPLATE_PACKAGE + '.' + SYSTEM_PACKAGE;
    private boolean mPreloadTemplates;
    //private boolean mRemoteSuccess;
    //private boolean mDelegatedSuccess;
    private String[] mTemplateURLs;
    private String mDefaultTemplateName;
    private String mEncoding;
    private long mPrecompiledTolerance;
    //private ClassInjector mInjector;
    private TemplateSource[] mCustomTemplateSources;
    private ReloadLock mReloadLock;
    private static long mTimeout;

    public static TeaServletTemplateSource createTemplateSource(TeaServletContextSource contextSrc,
            PropertyMap properties,
            Log log) {

        mTimeout = properties.getNumber("server.timeout",
                new Long(15000)).longValue();
        TemplateSourceConfig tsConfig = new TSConfig(contextSrc,
                properties,
                log);

        File destDir = TemplateSourceImpl.createTemplateClassesDir(properties.getString("classes"), log);


        TemplateSource[] customTemplateSources =
                createCustomTemplateSources(tsConfig);

        String sourcePathString = properties.getString("path");
        File[] localDirs = null;
        String[] remoteDirs = null;

        if (sourcePathString != null) {
            StringTokenizer sourcePathTokenizer =
                    new StringTokenizer(sourcePathString, ",;");

            Vector remoteVec = new Vector();
            Vector localVec = new Vector();

            // Sort out the local directories from those using http.
            while (sourcePathTokenizer.hasMoreTokens()) {
                String nextPath = sourcePathTokenizer.nextToken().trim();
                if (nextPath.startsWith("http://")) {
                    remoteVec.add(nextPath);
                } else {
                    localVec.add(new File(nextPath));
                }
            }

            localDirs = (File[]) localVec.toArray(new File[localVec.size()]);
            remoteDirs = (String[]) remoteVec.toArray(new String[remoteVec.size()]);
        }

        return new TeaServletTemplateSource(tsConfig, localDirs,
                remoteDirs, destDir,
                customTemplateSources);
    }

    private static TemplateSource[] createCustomTemplateSources(final TemplateSourceConfig config) {

        final PropertyMap props = config.getProperties().subMap("sources");
        List results = new Vector();
        Iterator nameIt = props.subMapKeySet().iterator();
        while (nameIt.hasNext()) {
            try {
                final String name = (String) nameIt.next();
                String className = props.getString(name + ".class");
                Class tsClass = config.getContextSource().getContextType().getClassLoader().loadClass(className);
                TemplateSource tsObj = (TemplateSource) tsClass.newInstance();
                tsObj.init(new TemplateSourceConfig() {

                    private Log mLog = new Log(name, config.getLog());
                    private PropertyMap mProps = props.subMap(name + ".init");

                    public PropertyMap getProperties() {
                        return mProps;
                    }

                    public Log getLog() {
                        return mLog;
                    }

                    public ContextSource getContextSource() {
                        return config.getContextSource();
                    }

                    public String getPackagePrefix() {
                        return config.getPackagePrefix();
                    }

                    public boolean isExceptionGuardianEnabled() {
                        return config.isExceptionGuardianEnabled();
                    }
                });
                results.add(tsObj);
            } catch (Exception e) {
                config.getLog().warn(e);
            }
        }
        if (results == null) {
            config.getLog().debug("null results vector");
        }

        TemplateSource[] tSrc = (TemplateSource[]) results.toArray(new TemplateSource[results.size()]);
        if (tSrc == null) {
            config.getLog().debug("null results array");
            tSrc = new TemplateSource[0];
        }
        return tSrc;
    }

    private TeaServletTemplateSource(TemplateSourceConfig config,
            File[] localTemplateDirs,
            String[] remoteTemplateURLs,
            File compiledTemplateDir,
            TemplateSource[] customSources) {

        super();

        //since I'm not calling init to parse the config, set things up.
        mConfig = config;
        mLog = config.getLog();
        mProperties = config.getProperties();
        mLog.info("initializing the TeaServletTemplateSource.");

        mReloadLock = new ReloadLock();
        setTemplateRootDirs(localTemplateDirs);
        setDestinationDirectory(compiledTemplateDir);

        if (customSources == null) {
            mLog.debug("No custom TemplateSources configured.");
        } else {
            mLog.info(customSources.length + " custom TemplateSources configured.");
        }

        mCustomTemplateSources = customSources;
        mTemplateURLs = remoteTemplateURLs;
        mDefaultTemplateName = config.getProperties().getString("default");
        mEncoding = config.getProperties().getString("file.encoding", "ISO-8859-1");
        mPreloadTemplates = config.getProperties().getBoolean("preload", true);
        mPrecompiledTolerance = config.getProperties().getInt("precompiled.tolerance", 1000);

        if (mCompiledDir == null && !mPreloadTemplates) {
            mLog.warn("Now preloading templates.");
            mPreloadTemplates = true;
        }
    }

    public int getKnownTemplateCount() {
        int total = super.getKnownTemplateCount();
        for (int j = 0; j < mCustomTemplateSources.length; j++) {
            total += mCustomTemplateSources[j].getKnownTemplateCount();
        }
        return total;
    }

    public String[] getKnownTemplateNames() {
        String[] allNames = new String[getKnownTemplateCount()];
        String[] names = super.getKnownTemplateNames();
        System.arraycopy(names, 0, allNames, 0, names.length);
        int pos = names.length;
        for (int j = 0; j < mCustomTemplateSources.length; j++) {
            names = mCustomTemplateSources[j].getKnownTemplateNames();
            System.arraycopy(names, 0, allNames, pos, names.length);
            pos += names.length;
        }

        if (pos < allNames.length) {
            String[] tmp = new String[pos];
            if (pos > 0) {
                System.arraycopy(allNames, 0, tmp, 0, pos);
            }
            allNames = tmp;
        }
        return allNames;
    }

    public TemplateCompilationResults compileTemplates(ClassInjector commonInjector,
            boolean all) throws Exception {

        return compileTemplates(commonInjector, all, true, true);
    }

    public TemplateCompilationResults compileTemplates(ClassInjector injector,
            String[] selectedTemplates) throws Exception {

        return compileTemplates(injector, false, false, true, selectedTemplates);
    }

    public TemplateCompilationResults checkTemplates(ClassInjector injector,
            boolean all,
            String[] selectedTemplates)
            throws Exception {
        // TODO should we synch with reloading?
        TemplateCompilationResults results = super.checkTemplates(injector, all, selectedTemplates);

        if (mTemplateURLs != null && mTemplateURLs.length > 0) {
            if (injector == null) {
                injector = createClassInjector();
            }

            TemplateErrorListener errorListener = createErrorListener();

            RemoteCompiler compiler = new RemoteCompiler(mTemplateURLs,
                    TEMPLATE_PACKAGE,
                    mCompiledDir,
                    injector,
                    mEncoding,
                    mTimeout,
                    mPrecompiledTolerance);
            compiler.setClassLoader(injector);
            compiler.setRuntimeContext(getContextSource().getContextType());
            compiler.setCodeGenerationEnabled(false);
            compiler.addErrorListener(errorListener);
            compiler.setForceCompile(all);

            String[] templates;
            if (selectedTemplates == null || selectedTemplates.length == 0) {
                templates = compiler.getAllTemplateNames();
            } else {
                templates = selectedTemplates;
                compiler.setForceCompile(true);
            }

            List callerList = new ArrayList();

            templateLoop:
            for (int i = 0; i < templates.length; i++) {

                CompilationUnit unit = compiler.getCompilationUnit(templates[i], null);
                if (unit == null) {
                    mLog.warn("selected template not found: " + templates[i]);
                    continue templateLoop;
                }

                if (unit.shouldCompile() && !results.getReloadedTemplateNames().contains(templates[i])) {

                    compiler.getParseTree(unit);

                    results.appendName(templates[i]);
                    callerList.addAll(Arrays.asList(TemplateRepository.getInstance().getCallers(unit.getName())));
                }
            }

            compiler.setForceCompile(true);
            callerLoop:
            for (Iterator it = callerList.iterator(); it.hasNext();) {
                TemplateInfo tInfo = (TemplateInfo) it.next();
                String caller = tInfo.getShortName().replace('/', '.');
                if (results.getReloadedTemplateNames().contains(caller)) {
                    continue callerLoop;
                }

                CompilationUnit callingUnit = compiler.getCompilationUnit(caller, null);
                if (callingUnit != null) {
                    compiler.getParseTree(callingUnit);
                }
            }

            results.appendErrors(errorListener.getTemplateErrors());
            errorListener.close();

        }
        
        results.getReloadedTemplateNames().removeAll(results.getTemplateErrors().keySet());

        return results;
    }

    private TemplateCompilationResults compileTemplates(
            ClassInjector commonInjector,
            boolean all,
            boolean recurse,
            boolean enforceReloadLock)
            throws Exception {
        return compileTemplates(commonInjector, all, recurse, enforceReloadLock, null);
    }

    private TemplateCompilationResults compileTemplates(
            ClassInjector commonInjector,
            boolean all,
            boolean recurse,
            boolean enforceReloadLock,
            String[] selectedTemplates)
            throws Exception {

        synchronized (mReloadLock) {
            if (mReloadLock.isReloading() && enforceReloadLock) {
                return new TemplateCompilationResults();
            } else {
                mReloadLock.setReloading(true);
            }
        }

        mLog.info("Reloading Templates:");

        try {
            if (commonInjector == null) {
                commonInjector = createClassInjector();
            } else {
                mLog.debug("at this point, the injector should still be null since the template source delegation starts here");
            }

            Results results;
            boolean isSelectiveCompile = null != selectedTemplates && selectedTemplates.length > 0;
            if (isSelectiveCompile) {
                results = actuallyCompileTemplates(commonInjector, selectedTemplates);
            } else {
                results = actuallyCompileTemplates(commonInjector, all, recurse);
            }

            for (int j = 0; j < mCustomTemplateSources.length; j++) {
                TemplateCompilationResults delegateResults =
                        mCustomTemplateSources[j].compileTemplates(commonInjector, all);
                if (delegateResults.isAlreadyReloading()) {
                    return delegateResults;
                } else {
                    TemplateCompilationResults transients =
                            results.getTransientResults();
                    transients.appendNames(delegateResults.getReloadedTemplateNames());
                    transients.appendErrors(delegateResults.getTemplateErrors());
                }
            }

            if (isSelectiveCompile) {
                compileRemoteTemplates(all, commonInjector, results, selectedTemplates);
            } else {
                compileRemoteTemplates(all, commonInjector, results);
            }

            // process results
            
            TemplateCompilationResults templateCompilationResults =
                    results.getTransientResults();

            // remove errors from reloaded
            templateCompilationResults.getReloadedTemplateNames()
                    .removeAll(templateCompilationResults.getTemplateErrors().keySet());
            
            String[] succeeded 
                    = (String[]) templateCompilationResults.getReloadedTemplateNames()
                        .toArray(new String[templateCompilationResults.getReloadedTemplateNames().size()]);

            mResults = results;

            if (mPreloadTemplates) {

                try {
                    if (templateCompilationResults.isSuccessful()) {
                        if (isSelectiveCompile) {
                            preloadTemplates(selectedTemplates);
                        } else {
                            preloadTemplates(this);
                        }
                    } else {
                        preloadTemplates(succeeded);
                    }

                    for (int j = 0; j < mCustomTemplateSources.length; j++) {
                        preloadTemplates(mCustomTemplateSources[j]);
                    }

                } catch (Throwable t) {
                    if (all == false) {
                        return compileTemplates(null, true, true, false);
                    }
                    mLog.error(t);
                }
            }

            if ( succeeded.length > 0 ) {
                
                mLog.info(""+ succeeded.length + " templates successfully reloaded:");
                
                for (int i = 0; i < succeeded.length; i++) {
                    mLog.info("\t" + succeeded[i]);
                }

            } else {
                mLog.info("No templates needed to be reloaded.");
                
            }

            if ( ! templateCompilationResults.isSuccessful()) {
                List errors = templateCompilationResults.getAllTemplateErrors();
                mLog.warn(errors.size() + " errors encountered.");
                Iterator errorIt = errors.iterator();
                while (errorIt.hasNext()) {
                    TemplateError error = (TemplateError) errorIt.next();
                    mLog.warn(error.getDetailedErrorMessage() + " : " + error.getSourceLine());
                }
            }

            return templateCompilationResults;
        } finally {
            synchronized (mReloadLock) {
                mReloadLock.setReloading(false);
            }
        }
    }

    public String getDefaultTemplateName() {
        return mDefaultTemplateName;
    }

    protected TemplateErrorListener createErrorListener() {
        return new RemoteTemplateErrorRetriever();
    }

    
    private void preloadTemplates(TemplateSource ts)
            throws Throwable {

        String[] knownTemplateNames = ts.getKnownTemplateNames();
        preloadTemplates(knownTemplateNames);
    }

    private void preloadTemplates(String[] selectedTemplates)
            throws Throwable {

        for (int j = 0; j < selectedTemplates.length; j++) {
            getTemplate(selectedTemplates[j]);
        }
    }

    private void compileRemoteTemplates(boolean force,
            ClassInjector injector,
            Results results)
            throws Exception {
        compileRemoteTemplates(force, injector, results, null);
    }

    private void compileRemoteTemplates(boolean force,
            ClassInjector injector,
            Results results,
            String[] selectedTemplates)
            throws Exception {

        if (mTemplateURLs != null && mTemplateURLs.length > 0) {
            TemplateErrorListener errorListener = createErrorListener();
            RemoteCompiler rmcomp =
                    new RemoteCompiler(mTemplateURLs,
                    TEMPLATE_PACKAGE,
                    mCompiledDir,
                    injector,
                    mEncoding,
                    mTimeout,
                    mPrecompiledTolerance);
            rmcomp.setClassLoader(injector);
            rmcomp.setRuntimeContext(getContextSource().getContextType());
            rmcomp.setExceptionGuardianEnabled(mConfig.isExceptionGuardianEnabled());
            rmcomp.addErrorListener(errorListener);
            if(isLogCompileStatus()) {
                rmcomp.addStatusListener(new CompilerStatusLogger(Arrays.toString(mTemplateURLs)));
            }
            
            rmcomp.setForceCompile(force);
            results.getKnownTemplateNames().addAll(Arrays.asList(rmcomp.getAllTemplateNames()));
            TemplateCompilationResults transients =
                    results.getTransientResults();
            if (null == selectedTemplates || selectedTemplates.length == 0) {
                transients.appendNames(Arrays.asList(rmcomp.compileAll()));
            } else {
                transients.appendNames(Arrays.asList(rmcomp.compile(selectedTemplates)));
            }
            transients.appendErrors(errorListener.getTemplateErrors());
        }
    }

    private static class TSConfig implements TemplateSourceConfig {

        private ContextSource mContextSource;
        private PropertyMap mProperties;
        private Log mLog;

        TSConfig(ContextSource contextSource,
                PropertyMap properties, Log log) {
            mContextSource = contextSource;
            mProperties = properties;
            mLog = log;
        }

        public ContextSource getContextSource() {
            return mContextSource;
        }

        public String getPackagePrefix() {
            return TEMPLATE_PACKAGE;
        }

        public boolean isExceptionGuardianEnabled() {
            return mProperties.getBoolean("exception.guardian", false);
        }

        public PropertyMap getProperties() {
            return mProperties;
        }

        public Log getLog() {
            return mLog;
        }
    }

    public Map listTouchedTemplates() throws Exception {
        TemplateRepository tRepo = TemplateRepository.getInstance();

        Map touchedTemplateMap = super.listTouchedTemplates();

        RemoteCompiler compiler = new RemoteCompiler(mTemplateURLs, TEMPLATE_PACKAGE, mCompiledDir, null, mEncoding, mTimeout, mPrecompiledTolerance);
        compiler.setForceCompile(false);

        String[] tNames = compiler.getAllTemplateNames();
        for (int i = 0; i < tNames.length; i++) {

            CompilationUnit unit = compiler.getCompilationUnit(tNames[i], null);

            if (unit.shouldCompile()) {
                Boolean sigChanged = Boolean.valueOf(sourceSignatureChanged(unit.getName(), compiler));
                touchedTemplateMap.put(unit.getName(), sigChanged);
            }
        }

        return touchedTemplateMap;
    }
    
    class RemoteTemplateErrorRetriever extends ErrorRetriever {
        
        public void compileError(ErrorEvent event) {
            if(! (event.getCompilationUnit() instanceof RemoteCompiler.Unit)) {
                super.compileError(event);
                return;
            }
            
            mConfig.getLog().warn("Error in " +
                                  event.getDetailedErrorMessage());

            RemoteCompiler.Unit unit = (RemoteCompiler.Unit) event.getCompilationUnit();
            if (unit == null) {
                return;
            }

            String templateName = unit.getName();
            
            ArrayList errors = (ArrayList) mTemplateErrors.get(templateName);
            if (errors == null) {
                errors = new ArrayList();
                mTemplateErrors.put(templateName, errors);
            }

            String sourcePath = unit.getSourceUrl();

            TemplateError templateError = createTemplateError(sourcePath, event);

            if (templateError != null) {
                errors.add(templateError);
            }
            
        }
    }
}


