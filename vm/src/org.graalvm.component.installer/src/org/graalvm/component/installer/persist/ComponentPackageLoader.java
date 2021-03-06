/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.component.installer.persist;

import java.io.BufferedReader;
import org.graalvm.component.installer.MetadataException;
import org.graalvm.component.installer.InstallerStopException;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.Function;
import org.graalvm.component.installer.Archive;
import org.graalvm.component.installer.BundleConstants;
import org.graalvm.component.installer.Feedback;
import org.graalvm.component.installer.SystemUtils;
import org.graalvm.component.installer.model.ComponentInfo;

/**
 * Loads information from the component's bundle.
 */
public class ComponentPackageLoader implements Closeable, MetadataLoader {
    private final Feedback feedback;

    /**
     * Default value producer.
     */
    private final Function<String, String> valueSupplier;

    /**
     * Do not throw exceptions eagerly.
     */
    private boolean infoOnly;

    /**
     * List of errors encountered when reading metadata. Filled only if {@link #infoOnly} =
     * {@code true}.
     */
    private final List<InstallerStopException> errors = new ArrayList<>();
    private final List<String> fileList = new ArrayList<>();

    /**
     * Component ID; temporary.
     */
    private String id;
    private String version;
    private String name;

    /**
     * Path for the LICENSE file; adjusted with version etc.
     */
    private String licensePath;

    /**
     * Type / name of the license.
     */
    private String licenseType;

    /**
     * The produced component info.
     */
    private ComponentInfo info;

    /**
     * If true, will not verify symbolic links.
     *
     * Default false = verify on.
     */
    private boolean noVerifySymlinks;

    static final ResourceBundle BUNDLE = ResourceBundle.getBundle("org.graalvm.component.installer.persist.Bundle");

    public ComponentPackageLoader(Function<String, String> supplier, Feedback feedback) {
        this.feedback = feedback.withBundle(ComponentPackageLoader.class);
        this.valueSupplier = supplier;
    }

    @Override
    public Archive getArchive() {
        return null;
    }

    @Override
    public ComponentPackageLoader infoOnly(boolean only) {
        this.infoOnly = only;
        return this;
    }

    private HeaderParser parseHeader(String header) throws MetadataException {
        String s = valueSupplier.apply(header);
        return new HeaderParser(header, s, feedback).mustExist();
    }

    private HeaderParser parseHeader(String header, String defValue) throws MetadataException {
        String s = valueSupplier.apply(header);
        if (s == null) {
            if (defValue == null) {
                return new HeaderParser(header, s, feedback);
            } else {
                return new HeaderParser(header, defValue, feedback);
            }
        }
        return new HeaderParser(header, s, feedback).mustExist();
    }

    @Override
    public ComponentInfo getComponentInfo() {
        if (info == null) {
            return createComponentInfo();
        }
        return info;
    }

    private void parse(Runnable... parts) {
        for (Runnable r : parts) {
            try {
                r.run();
            } catch (MetadataException ex) {
                if (BundleConstants.BUNDLE_ID.equals(ex.getOffendingHeader())) {
                    throw ex;
                }
                if (infoOnly) {
                    errors.add(ex);
                } else {
                    throw ex;
                }
            } catch (InstallerStopException ex) {
                if (infoOnly) {
                    errors.add(ex);
                } else {
                    throw ex;
                }
            }
        }
    }

    @Override
    public List<InstallerStopException> getErrors() {
        return errors;
    }

    private void loadWorkingDirectories() {
        String val = parseHeader(BundleConstants.BUNDLE_WORKDIRS, null).getContents("");
        Set<String> workDirs = new LinkedHashSet<>();
        for (String s : val.split(":")) { // NOI18N
            String p = s.trim();
            if (!p.isEmpty()) {
                workDirs.add(p);
            }
        }
        info.addWorkingDirectories(workDirs);
    }

    public ComponentInfo createComponentInfo() {
        parse(
                        () -> id = parseHeader(BundleConstants.BUNDLE_ID).parseSymbolicName(),
                        () -> name = parseHeader(BundleConstants.BUNDLE_NAME).getContents(id),
                        () -> version = parseHeader(BundleConstants.BUNDLE_VERSION).version(),
                        () -> {
                            info = new ComponentInfo(id, name, version);
                            info.addRequiredValues(parseHeader(BundleConstants.BUNDLE_REQUIRED).parseRequiredCapabilities());
                        },
                        () -> info.setPolyglotRebuild(parseHeader(BundleConstants.BUNDLE_POLYGLOT_PART, null).getBoolean(Boolean.FALSE)),
                        () -> loadWorkingDirectories(),
                        () -> loadMessages(),
                        () -> loadLicenseType()

        );
        return info;
    }

    private void loadLicenseType() {
        licenseType = parseHeader(BundleConstants.BUNDLE_LICENSE_TYPE, null).getContents(null);
    }

    @Override
    public String getLicensePath() {
        return licensePath;
    }

    @Override
    public String getLicenseID() {
        return null;
    }

    @Override
    public String getLicenseType() {
        return licenseType;
    }

    private void throwInvalidPermissions() {
        throw feedback.failure("ERROR_PermissionFormat", null);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, String> parsePermissions(BufferedReader r) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();

        Properties prop = new Properties();
        prop.load(r);

        List<String> paths = new ArrayList<>((Collection<String>) Collections.list(prop.propertyNames()));
        Collections.sort(paths);

        for (String k : paths) {
            SystemUtils.fromCommonRelative(k);
            String v = prop.getProperty(k, "").trim(); // NOI18N
            if (!v.isEmpty()) {
                try {
                    PosixFilePermissions.fromString(v);
                } catch (IllegalArgumentException ex) {
                    throwInvalidPermissions();
                }
            }
            result.put(k, v);
        }
        return result;
    }

    @Override
    public Map<String, String> loadPermissions() throws IOException {
        return Collections.emptyMap();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected Map<String, String> parseSymlinks(Properties links) {
        for (String key : new HashSet<>(links.stringPropertyNames())) {
            Path p = SystemUtils.fromCommonRelative(key).normalize();
            String prop = (String) links.remove(key);
            links.setProperty(SystemUtils.toCommonPath(p), prop);
        }
        if (noVerifySymlinks) {
            return new HashMap(links);
        }
        // check the links
        for (String s : Collections.list((Enumeration<String>) links.propertyNames())) {
            String l = s;
            Set<String> seen = new HashSet<>();
            while (l != null) {
                if (!seen.add(l)) {
                    throw feedback.failure("ERROR_CircularSymlink", null, l);
                }
                String target = links.getProperty(l);
                Path linkPath = SystemUtils.fromCommonRelative(l);
                SystemUtils.checkCommonRelative(linkPath, target);
                Path targetPath = linkPath.resolveSibling(target).normalize();
                String targetString = SystemUtils.toCommonPath(targetPath);
                if (fileList.contains(targetString)) {
                    break;
                }
                String lt = links.getProperty(targetString);
                if (lt == null) {
                    throw feedback.failure("ERROR_BrokenSymlink", null, target);
                }
                l = targetString;
            }
        }
        return new HashMap(links);
    }

    @Override
    public Map<String, String> loadSymlinks() throws IOException {
        return Collections.emptyMap();
    }

    @Override
    public void loadPaths() {
        getComponentInfo();
    }

    @Override
    public boolean isNoVerifySymlinks() {
        return noVerifySymlinks;
    }

    @Override
    public void setNoVerifySymlinks(boolean noVerifySymlinks) {
        this.noVerifySymlinks = noVerifySymlinks;
    }

    @Override
    public void close() throws IOException {
    }

    private void loadMessages() {
        String val = parseHeader(BundleConstants.BUNDLE_MESSAGE_POSTINST, null).getContents(null);
        if (val != null) {
            String text = val.replace("\\n", "\n").replace("\\\\", "\\"); // NOI18N
            info.setPostinstMessage(text);
        }
    }

    protected void setLicensePath(String path) {
        this.licensePath = path;
        getComponentInfo().setLicensePath(licensePath);
    }

    protected void addFiles(List<String> files) {
        fileList.addAll(files);
    }
}
