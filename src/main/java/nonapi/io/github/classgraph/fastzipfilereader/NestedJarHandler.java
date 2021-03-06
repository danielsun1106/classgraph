/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package nonapi.io.github.classgraph.fastzipfilereader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Inflater;

import io.github.classgraph.ModuleReaderProxy;
import io.github.classgraph.ModuleRef;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;
import nonapi.io.github.classgraph.ScanSpec;
import nonapi.io.github.classgraph.concurrency.SingletonMap;
import nonapi.io.github.classgraph.recycler.Recycler;
import nonapi.io.github.classgraph.recycler.RecyclerExceptionless;
import nonapi.io.github.classgraph.utils.FastPathResolver;
import nonapi.io.github.classgraph.utils.FileUtils;
import nonapi.io.github.classgraph.utils.LogNode;
import nonapi.io.github.classgraph.utils.VersionFinder;
import nonapi.io.github.classgraph.utils.VersionFinder.OperatingSystem;

/** Open and read jarfiles, which may be nested within other jarfiles. */
public class NestedJarHandler {
    /** A singleton map from a zipfile's {@link File} to the {@link PhysicalZipFile} for that file. */
    private SingletonMap<File, PhysicalZipFile> canonicalFileToPhysicalZipFileMap;

    /** {@link PhysicalZipFile} instances created to extract nested jarfiles to disk or RAM. */
    private Queue<PhysicalZipFile> additionalAllocatedPhysicalZipFiles;

    /**
     * A singleton map from a {@link FastZipEntry} to the {@link ZipFileSlice} wrapping either the zip entry data,
     * if the entry is stored, or a ByteBuffer, if the zip entry was inflated to memory, or a physical file on disk
     * if the zip entry was inflated to a temporary file.
     */
    private SingletonMap<FastZipEntry, ZipFileSlice> fastZipEntryToZipFileSliceMap;

    /** A singleton map from a {@link ZipFileSlice} to the {@link LogicalZipFile} for that slice. */
    private SingletonMap<ZipFileSlice, LogicalZipFile> zipFileSliceToLogicalZipFileMap;

    /** All allocated LogicalZipFile instances. */
    private Queue<LogicalZipFile> allocatedLogicalZipFiles;

    /**
     * A singleton map from nested jarfile path to a tuple of the logical zipfile for the path, and the package root
     * within the logical zipfile.
     */
    public SingletonMap<String, Entry<LogicalZipFile, String>> //
    nestedPathToLogicalZipFileAndPackageRootMap;

    /** A singleton map from a {@link ModuleRef} to a {@link ModuleReaderProxy} recycler for the module. */
    public SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>> //
    moduleRefToModuleReaderProxyRecyclerMap;

    /** A recycler for {@link Inflater} instances. */
    public RecyclerExceptionless<RecyclableInflater> inflaterRecycler;

    /**
     * The number of {@link MappedByteBuffer} instances whose references have been dropped, to allow them to be
     * garbage collected (causing them to be un-mmap'd / released during GC).
     */
    private final AtomicInteger numMmapedRefsFreed;

    /** Any temporary files created while scanning. */
    private ConcurrentLinkedDeque<File> tempFiles = new ConcurrentLinkedDeque<>();

    /** The separator between random temp filename part and leafname. */
    public static final String TEMP_FILENAME_LEAF_SEPARATOR = "---";

    /**
     * The threshold uncompressed size at which nested deflated jars are inflated to a temporary file on disk,
     * rather than to RAM.
     */
    private static final int INFLATE_TO_DISK_THRESHOLD = 32 * 1024 * 1024;

    /** True if {@link #close(LogNode)} has been called. */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // -------------------------------------------------------------------------------------------------------------

    /**
     * A handler for nested jars.
     * 
     * @param scanSpec
     *            The {@link ScanSpec}.
     * @param log
     *            The log.
     */
    public NestedJarHandler(final ScanSpec scanSpec, final LogNode log) {
        this.numMmapedRefsFreed = new AtomicInteger(0);

        // Set up a recycler for Inflater instances.
        this.inflaterRecycler = new RecyclerExceptionless<RecyclableInflater>() {
            @Override
            public RecyclableInflater newInstance() throws RuntimeException {
                if (closed.get()) {
                    throw new RuntimeException(getClass().getSimpleName() + " already closed");
                }
                return new RecyclableInflater();
            }
        };

        // Set up a singleton map from canonical File to PhysicalZipFile instance, so that the RandomAccessFile
        // and FileChannel for any given zipfile is opened only once
        this.canonicalFileToPhysicalZipFileMap = new SingletonMap<File, PhysicalZipFile>() {
            @Override
            public PhysicalZipFile newInstance(final File canonicalFile, final LogNode log) throws IOException {
                if (closed.get()) {
                    throw new IOException(getClass().getSimpleName() + " already closed");
                }
                return new PhysicalZipFile(canonicalFile, NestedJarHandler.this);
            }
        };

        // Allocate a queue of all allocated LogicalZipFile instances
        this.allocatedLogicalZipFiles = new ConcurrentLinkedQueue<>();

        // Allocate a queue of additional allocated PhysicalZipFile instances
        this.additionalAllocatedPhysicalZipFiles = new ConcurrentLinkedQueue<>();

        // Set up a singleton map that wraps nested stored jarfiles in a ZipFileSlice, or inflates nested
        // deflated jarfiles to a temporary file if they are large, or inflates nested deflated jarfiles
        // to RAM if they are small (or if a temporary file could not be created)
        this.fastZipEntryToZipFileSliceMap = new SingletonMap<FastZipEntry, ZipFileSlice>() {
            @Override
            public ZipFileSlice newInstance(final FastZipEntry childZipEntry, final LogNode log) throws Exception {
                ZipFileSlice childZipEntrySlice;
                if (!childZipEntry.isDeflated) {
                    // Wrap the child entry (a stored nested zipfile) in a new ZipFileSlice -- there is
                    // nothing else to do. (Most nested zipfiles are stored, not deflated, so this fast
                    // path will be followed most often.)
                    childZipEntrySlice = new ZipFileSlice(childZipEntry);

                } else {
                    // If child entry is deflated i.e. (for a deflated nested zipfile), must inflate
                    // the contents of the entry before its central directory can be read (most of
                    // the time nested zipfiles are stored, not deflated, so this should be rare)
                    if ((childZipEntry.uncompressedSize < 0L
                            || childZipEntry.uncompressedSize >= INFLATE_TO_DISK_THRESHOLD
                    // Also check compressed size for safety, in case uncompressed size is wrong
                            || childZipEntry.compressedSize >= INFLATE_TO_DISK_THRESHOLD)) {
                        // If child entry's size is unknown or the file is large, inflate to disk
                        File tempFile = null;
                        try {
                            // Create temp file
                            tempFile = makeTempFile(childZipEntry.entryName, /* onlyUseLeafname = */ true);

                            // Inflate zip entry to temp file
                            if (log != null) {
                                log.log("Deflating zip entry to temporary file: " + childZipEntry
                                        + " ; uncompressed size: " + childZipEntry.uncompressedSize
                                        + " ; temp file: " + tempFile);
                            }
                            try (InputStream inputStream = childZipEntry.open()) {
                                Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            }

                            // Get or create a PhysicalZipFile instance for the new temp file
                            final PhysicalZipFile physicalZipFile = canonicalFileToPhysicalZipFileMap.get(tempFile,
                                    log);
                            additionalAllocatedPhysicalZipFiles.add(physicalZipFile);

                            // Create a new logical slice of the whole physical zipfile
                            childZipEntrySlice = new ZipFileSlice(physicalZipFile);

                        } catch (final IOException e) {
                            // Could not make temp file, or failed to extract entire contents of entry
                            if (tempFile != null) {
                                // Delete temp file, in case it contains partially-extracted data
                                // due to running out of disk space
                                tempFile.delete();
                            }
                            childZipEntrySlice = null;
                        }
                    } else {
                        childZipEntrySlice = null;
                    }
                    if (childZipEntrySlice == null) {
                        // If the uncompressed size known and small, or inflating to temp file failed,
                        // inflate to a ByteBuffer in memory instead
                        if (childZipEntry.uncompressedSize > FileUtils.MAX_BUFFER_SIZE) {
                            // Impose 2GB limit (i.e. a max of one ByteBuffer chunk) on inflation to memory
                            throw new IOException(
                                    "Uncompressed size of zip entry (" + childZipEntry.uncompressedSize
                                            + ") is too large to inflate to memory: " + childZipEntry.entryName);
                        }

                        // Open the zip entry to fetch inflated data, and read the whole contents of the
                        // InputStream to a byte[] array, then wrap it in a ByteBuffer
                        if (log != null) {
                            log.log("Deflating zip entry to RAM: " + childZipEntry + " ; uncompressed size: "
                                    + childZipEntry.uncompressedSize);
                        }
                        ByteBuffer byteBuffer;
                        try (InputStream inputStream = childZipEntry.open()) {
                            byteBuffer = ByteBuffer.wrap(
                                    FileUtils.readAllBytesAsArray(inputStream, childZipEntry.uncompressedSize));
                        }

                        // Create a new PhysicalZipFile that wraps the ByteBuffer as if the buffer had been
                        // mmap'd to a file on disk
                        final PhysicalZipFile physicalZipFileInRam = new PhysicalZipFile(byteBuffer,
                                /* outermostFile = */ childZipEntry.parentLogicalZipFile.physicalZipFile.getFile(),
                                childZipEntry.getPath(), NestedJarHandler.this);
                        additionalAllocatedPhysicalZipFiles.add(physicalZipFileInRam);

                        // Create a new logical slice of the whole physical in-memory zipfile
                        childZipEntrySlice = new ZipFileSlice(physicalZipFileInRam, childZipEntry);
                    }
                }
                return childZipEntrySlice;
            }
        };

        // Set up a singleton map from ZipFileSlice to LogicalZipFile instance, so that LogicalZipFile instances
        // are reused rather than duplicated
        this.zipFileSliceToLogicalZipFileMap = new SingletonMap<ZipFileSlice, LogicalZipFile>() {
            @Override
            public LogicalZipFile newInstance(final ZipFileSlice zipFileSlice, final LogNode log) throws Exception {
                if (closed.get()) {
                    throw new IOException(getClass().getSimpleName() + " already closed");
                }
                // Read the central directory for the logical zipfile slice
                final LogicalZipFile logicalZipFile = new LogicalZipFile(zipFileSlice, scanSpec, log);
                allocatedLogicalZipFiles.add(logicalZipFile);
                return logicalZipFile;
            }
        };

        // Create a singleton map from path to zipfile File, in order to eliminate repeatedly unzipping the same
        // file when there are multiple jars-within-jars that need unzipping to temporary files.
        this.nestedPathToLogicalZipFileAndPackageRootMap = new SingletonMap< //
                String, Entry<LogicalZipFile, String>>() {
            @Override
            public Entry<LogicalZipFile, String> newInstance(final String nestedJarPathRaw, final LogNode log)
                    throws Exception {
                if (closed.get()) {
                    throw new IOException(getClass().getSimpleName() + " already closed");
                }
                final String nestedJarPath = FastPathResolver.resolve(nestedJarPathRaw);
                final int lastPlingIdx = nestedJarPath.lastIndexOf('!');
                if (lastPlingIdx < 0) {
                    // nestedJarPath is a simple file path or URL (i.e. doesn't have any '!' sections). This is also
                    // the last frame of recursion for the 'else' clause below.

                    // If the path starts with "http(s)://", download the jar to a temp file
                    final boolean isRemote = nestedJarPath.startsWith("http://")
                            || nestedJarPath.startsWith("https://");
                    File canonicalFile;
                    if (isRemote) {
                        // Jarfile is at http(s) URL
                        if (scanSpec.enableRemoteJarScanning) {
                            canonicalFile = downloadTempFile(nestedJarPath, log);
                            if (canonicalFile == null) {
                                throw new IOException("Could not download jarfile " + nestedJarPath);
                            }
                        } else {
                            throw new IOException(
                                    "Remote jar scanning has not been enabled, cannot scan classpath element URL: "
                                            + nestedJarPath);
                        }
                    } else {
                        // Jarfile should be local
                        try {
                            canonicalFile = new File(nestedJarPath).getCanonicalFile();
                        } catch (final SecurityException e) {
                            throw new IOException(
                                    "Path component " + nestedJarPath + " could not be canonicalized: " + e);
                        }
                    }
                    if (!FileUtils.canRead(canonicalFile)) {
                        throw new IOException("Path component " + nestedJarPath + " does not exist");
                    }
                    if (!canonicalFile.isFile()) {
                        throw new IOException(
                                "Path component " + nestedJarPath + "  is not a file (expected a jarfile)");
                    }

                    final LogNode zipFileLog = log == null ? null : log.log("Opening jarfile " + canonicalFile);

                    // Get or create a PhysicalZipFile instance for the canonical file
                    final PhysicalZipFile physicalZipFile = canonicalFileToPhysicalZipFileMap.get(canonicalFile,
                            zipFileLog);

                    // Create a new logical slice of the whole physical zipfile
                    final ZipFileSlice topLevelSlice = new ZipFileSlice(physicalZipFile);
                    final LogicalZipFile logicalZipFile = zipFileSliceToLogicalZipFileMap.get(topLevelSlice,
                            zipFileLog);

                    // Return new logical zipfile with an empty package root
                    return new SimpleEntry<>(logicalZipFile, "");

                } else {
                    // This path has one or more '!' sections.
                    final String parentPath = nestedJarPath.substring(0, lastPlingIdx);
                    String childPath = nestedJarPath.substring(lastPlingIdx + 1);
                    // "file.jar!/path" -> "file.jar!path"
                    childPath = FileUtils.sanitizeEntryPath(childPath);

                    // Recursively remove one '!' section at a time, back towards the beginning of the URL or
                    // file path. At the last frame of recursion, the toplevel jarfile will be reached and
                    // returned. The recursion is guaranteed to terminate because parentPath gets one
                    // '!'-section shorter with each recursion frame.
                    final Entry<LogicalZipFile, String> parentLogicalZipFileAndPackageRoot = //
                            nestedPathToLogicalZipFileAndPackageRootMap.get(parentPath, log);
                    if (parentLogicalZipFileAndPackageRoot == null) {
                        // Failed to get topmost jarfile, e.g. file not found
                        throw new IOException("Could not find parent jarfile " + parentPath);
                    }
                    // Only the last item in a '!'-delimited list can be a non-jar path, so the parent must
                    // always be a jarfile.
                    final LogicalZipFile parentLogicalZipFile = parentLogicalZipFileAndPackageRoot.getKey();
                    if (parentLogicalZipFile == null) {
                        // Failed to get topmost jarfile, e.g. file not found
                        throw new IOException("Could not find parent jarfile " + parentPath);
                    }

                    // Look up the child path within the parent zipfile
                    boolean isDirectory = false;
                    while (childPath.endsWith("/")) {
                        // Child path is definitely a directory, it ends with a slash 
                        isDirectory = true;
                        childPath = childPath.substring(0, childPath.length() - 1);
                    }
                    FastZipEntry childZipEntry = null;
                    if (!isDirectory) {
                        // If child path doesn't end with a slash, see if there's a non-directory entry
                        // with a name matching the child path (LogicalZipFile discards directory entries
                        // ending with a slash when reading the central directory of a zipfile)
                        for (final FastZipEntry entry : parentLogicalZipFile.getEntries()) {
                            if (entry.entryName.equals(childPath)) {
                                childZipEntry = entry;
                                break;
                            }
                        }
                    }
                    if (childZipEntry == null) {
                        // If there is no non-directory zipfile entry with a name matching the child path, 
                        // test to see if any entries in the zipfile have the child path as a dir prefix
                        final String childPathPrefix = childPath + "/";
                        for (final FastZipEntry entry : parentLogicalZipFile.getEntries()) {
                            if (entry.entryName.startsWith(childPathPrefix)) {
                                isDirectory = true;
                                break;
                            }
                        }
                        if (!isDirectory) {
                            throw new IOException(
                                    "Path " + childPath + " does not exist in jarfile " + parentLogicalZipFile);
                        }
                    }
                    // At this point, either isDirectory is true, or childZipEntry is non-null

                    // If path component is a directory, it is a package root
                    if (isDirectory) {
                        if (!childPath.isEmpty()) {
                            // Add directory path to parent jarfile root relative paths set
                            // (this has the side effect of adding this parent jarfile root
                            // to the set of roots for all references to the parent path)
                            if (log != null) {
                                log.log("Path " + childPath + " in jarfile " + parentLogicalZipFile
                                        + " is a directory, not a file -- using as package root");
                            }
                            parentLogicalZipFile.classpathRoots.add(childPath);
                        }
                        // Return parent logical zipfile, and child path as the package root
                        return new SimpleEntry<>(parentLogicalZipFile, childPath);
                    }

                    // Do not extract nested jar, if nested jar scanning is disabled
                    if (!scanSpec.scanNestedJars) {
                        throw new IOException(
                                "Nested jar scanning is disabled -- skipping nested jar " + nestedJarPath);
                    }

                    // The child path corresponds to a non-directory zip entry, so it must be a nested jar
                    // (since non-jar nested files cannot be used on the classpath). Map the nested jar as
                    // a new ZipFileSlice if it is stored, or inflate it to RAM or to a temporary file if
                    // it is deflated, then create a new ZipFileSlice over the temporary file or ByteBuffer.
                    try {
                        // Get zip entry as a ZipFileSlice, possibly inflating to disk or RAM
                        final ZipFileSlice childZipEntrySlice = fastZipEntryToZipFileSliceMap.get(childZipEntry,
                                log);
                        if (childZipEntrySlice == null) {
                            throw new IOException(
                                    "Could not open nested jarfile entry: " + childZipEntry.getPath());
                        }

                        final LogNode zipSliceLog = log == null ? null
                                : log.log("Getting zipfile slice " + childZipEntrySlice);

                        // Get or create a new LogicalZipFile for the child zipfile
                        final LogicalZipFile childLogicalZipFile = zipFileSliceToLogicalZipFileMap
                                .get(childZipEntrySlice, zipSliceLog);

                        // Return new logical zipfile with an empty package root
                        return new SimpleEntry<>(childLogicalZipFile, "");

                    } catch (final IOException e) {
                        // Thrown if the inner zipfile could nat be extracted
                        throw new IOException("File does not appear to be a zipfile: " + childPath);
                    }
                }
            }
        };

        // Set up a singleton map from ModuleRef object to ModuleReaderProxy recycler
        this.moduleRefToModuleReaderProxyRecyclerMap = //
                new SingletonMap<ModuleRef, Recycler<ModuleReaderProxy, IOException>>() {
                    @Override
                    public Recycler<ModuleReaderProxy, IOException> newInstance(final ModuleRef moduleRef,
                            final LogNode log) {
                        return new Recycler<ModuleReaderProxy, IOException>() {
                            @Override
                            public ModuleReaderProxy newInstance() throws IOException {
                                if (closed.get()) {
                                    throw new IOException(getClass().getSimpleName() + " already closed");
                                }
                                return moduleRef.open();
                            }
                        };
                    }

                };
    }

    // -------------------------------------------------------------------------------------------------------------

    private String leafname(final String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String sanitizeFilename(final String filename) {
        return filename.replace('/', '_').replace('\\', '_').replace(':', '_').replace('?', '_').replace('&', '_')
                .replace('=', '_').replace(' ', '_');
    }

    /**
     * Create a temporary file, and mark it for deletion on exit.
     * 
     * @param filePath
     *            The path to derive the temporary filename from.
     * @param onlyUseLeafname
     *            If true, only use the leafname of filePath to derive the temporary filename.
     * @return The temporary {@link File}.
     * @throws IOException
     *             If the temporary file could not be created.
     */
    private File makeTempFile(final String filePath, final boolean onlyUseLeafname) throws IOException {
        final File tempFile = File.createTempFile("ClassGraph--",
                TEMP_FILENAME_LEAF_SEPARATOR + sanitizeFilename(onlyUseLeafname ? leafname(filePath) : filePath));
        tempFile.deleteOnExit();
        tempFiles.add(tempFile);
        return tempFile;
    }

    /** Download a jar from a URL to a temporary file. */
    private File downloadTempFile(final String jarURL, final LogNode log) {
        final LogNode subLog = log == null ? null : log.log(jarURL, "Downloading URL " + jarURL);
        File tempFile;
        try {
            tempFile = makeTempFile(jarURL, /* onlyUseLeafname = */ true);
            final URL url = new URL(jarURL);
            try (InputStream inputStream = url.openStream()) {
                Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (subLog != null) {
                subLog.addElapsedTime();
            }
        } catch (final Exception e) {
            if (subLog != null) {
                subLog.log("Could not download " + jarURL, e);
            }
            return null;
        }
        if (subLog != null) {
            subLog.log("Downloaded to temporary file " + tempFile);
            subLog.log("***** Note that it is time-consuming to scan jars at http(s) addresses, "
                    + "they must be downloaded for every scan, and the same jars must also be "
                    + "separately downloaded by the ClassLoader *****");
        }
        return tempFile;
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * This method is called when a {@link MappedByteBuffer} reference is dropped, to enable it to be garbage
     * collected. If you run out of mmap'd buffers, it can cause a wide range of problems, including possibly
     * causing a VM crash, according to: <a href=<http://www.mapdb.org/blog/mmap_files_alloc_and_jvm_crash/">this
     * MapDB developer</a>. The limit on the number of concurrent mmap allocations is 64k on Linux. Therefore, to do
     * everything possible to prevent hitting this limit, call <code>System.gc()</code> every 20,000 mmap references
     * dropped.
     * 
     * <p>
     * (We have no way to know how many MappedByteBuffers are actually mmap'd at any given point in time, only how
     * many have been allocated, and how many references have been dropped for garbage collection -- so just call
     * System.gc() when it is possible there are a lot of mmap'd byte buffers that need to be unmapped.)
     * 
     * <p>
     * Most of the time, there only needs to be one MappedByteBuffer held mapped per jarfile, and up to one
     * MappedByteBuffer per worker thread (for the classfiles that are currently being read and parsed). Worker
     * threads only mmap classfiles if they are reading from a dir-based classpath entry, and if the classfile is
     * larger than 16kb. It would be quite rare to find a dir-based classpath entry containing over 20,000
     * classfiles larger than 16kb, so this <code>System.gc()</code> call should not be triggered very often.
     * 
     * <p>
     * Windows and Mac OS X do not seem to have an mmap limit (tested to 1M concurrent allocations in both cases).
     * However, mmap has major issues on Windows due to holding a file lock per mmap allocation, preventing files
     * from being deleted, etc., so mmap has been disabled for reading classfiles and resources from dir-based
     * classpath entries on Windows (Windows will only hold one mmap per jarfile).
     * 
     * <p>
     * The user could potentially trigger a large number of concurrent mmap allocations on Linux if they try opening
     * a large number of directory-based {@link Resource} objects using {@link Resource#read()}, whether or not the
     * resources are closed (since GC may not kick in). Users would probably have to open tens of thousands of
     * resources to risk running into issues though.
     * 
     * <p>
     * (Note that <code>System.gc()</code> doesn't even guarantee that a GC will be triggered, but this is the best
     * we can do to un-mmap a {@link MappedByteBuffer} after its reference has been dropped.)
     */
    public void freedMmapRef() {
        if (VersionFinder.OS == OperatingSystem.Linux && numMmapedRefsFreed.incrementAndGet() % 20_000 == 0) {
            System.gc();
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Close zipfiles, modules, and recyclers, and delete temporary files. Called by {@link ScanResult#close()}.
     * 
     * @param log
     *            The log.
     */
    public void close(final LogNode log) {
        if (!closed.getAndSet(true)) {
            if (inflaterRecycler != null) {
                inflaterRecycler.forceClose();
                inflaterRecycler = null;
            }
            if (moduleRefToModuleReaderProxyRecyclerMap != null) {
                try {
                    for (final Recycler<ModuleReaderProxy, IOException> recycler : //
                    moduleRefToModuleReaderProxyRecyclerMap.values()) {
                        recycler.forceClose();
                    }
                } catch (final InterruptedException e) {
                }
                moduleRefToModuleReaderProxyRecyclerMap.clear();
                moduleRefToModuleReaderProxyRecyclerMap = null;
            }
            if (zipFileSliceToLogicalZipFileMap != null) {
                zipFileSliceToLogicalZipFileMap.clear();
                zipFileSliceToLogicalZipFileMap = null;
            }
            if (nestedPathToLogicalZipFileAndPackageRootMap != null) {
                nestedPathToLogicalZipFileAndPackageRootMap.clear();
                nestedPathToLogicalZipFileAndPackageRootMap = null;
            }
            for (LogicalZipFile logicalZipFile; (logicalZipFile = allocatedLogicalZipFiles.poll()) != null;) {
                logicalZipFile.close();
            }
            if (canonicalFileToPhysicalZipFileMap != null) {
                try {
                    for (final PhysicalZipFile physicalZipFile : canonicalFileToPhysicalZipFileMap.values()) {
                        physicalZipFile.close();
                    }
                } catch (final InterruptedException e) {
                }
                canonicalFileToPhysicalZipFileMap.clear();
                canonicalFileToPhysicalZipFileMap = null;
            }
            if (additionalAllocatedPhysicalZipFiles != null) {
                for (PhysicalZipFile physicalZipFile; (physicalZipFile = additionalAllocatedPhysicalZipFiles
                        .poll()) != null;) {
                    physicalZipFile.close();
                }
                additionalAllocatedPhysicalZipFiles.clear();
                additionalAllocatedPhysicalZipFiles = null;
            }
            if (fastZipEntryToZipFileSliceMap != null) {
                fastZipEntryToZipFileSliceMap.clear();
                fastZipEntryToZipFileSliceMap = null;
            }
            if (VersionFinder.OS == OperatingSystem.Windows || VersionFinder.OS == OperatingSystem.Unknown) {
                // Need to perform a GC before removing temporary files, since mmap'd files cannot be deleted
                // on Windows, and GC is the only way to un-mmap a MappedByteBuffer (see #288)
                System.gc();
            }
            // Temp files have to be deleted last, after all possible refs to MappedByteBuffers are dropped
            if (tempFiles != null) {
                final LogNode rmLog = tempFiles.isEmpty() || log == null ? null
                        : log.log("Removing temporary files");
                while (!tempFiles.isEmpty()) {
                    final File tempFile = tempFiles.removeLast();
                    final String path = tempFile.getPath();
                    boolean success = false;
                    Throwable e = null;
                    try {
                        success = tempFile.delete();
                    } catch (final Throwable t) {
                        e = t;
                    }
                    if (rmLog != null) {
                        rmLog.log((success ? "Removed" : "Unable to remove") + " " + path
                                + (e == null ? "" : " : " + e));
                    }
                }
                tempFiles.clear();
                tempFiles = null;
            }
        }
    }
}
