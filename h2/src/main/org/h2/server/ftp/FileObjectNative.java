/*
 * Copyright 2004-2006 H2 Group. Licensed under the H2 License, Version 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.server.ftp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;

import org.h2.util.FileUtils;
import org.h2.util.IOUtils;

public class FileObjectNative implements FileObject {
    private File file;
    
    static FileObjectNative get(String name) {
        name = FileUtils.translateFileName(name);
        return new FileObjectNative(new File(name));
    }
    
    private FileObjectNative(File f) {
        this.file = f;
    }

    public boolean exists() {
        return file.exists();
    }

    public boolean isDirectory() {
        return file.isDirectory();
    }

    public boolean canRead() {
        return file.canRead();
    }

    public boolean canWrite() {
        return file.canWrite();
    }

    public boolean delete() {
        return file.delete();
    }

    public String getName() {
        return file.getName();
    }

    public boolean isFile() {
        return file.isFile();
    }

    public long lastModified() {
        return file.lastModified();
    }

    public long length() {
        return file.length();
    }

    public FileObject[] listFiles() {
        File[] list = file.listFiles();
        FileObject[] result = new FileObject[list.length];
        for(int i=0; i<list.length; i++) {
            result[i] = get(list[i].getAbsolutePath());
        }
        return result;
    }

    public boolean mkdirs() {
        return file.mkdirs();
    }

    public boolean renameTo(FileObject newFile) {
        return file.renameTo(((FileObjectNative)newFile).file);
    }

    public void write(InputStream in) throws IOException {
        try {
            OutputStream out = FileUtils.openFileOutputStream(file.getAbsolutePath());
            IOUtils.copyAndClose(in, out);
        } catch(SQLException e) {
            throw new IOException(e.getMessage());
        }
    }

    public void read(long skip, OutputStream out) throws IOException {
        InputStream in = FileUtils.openFileInputStream(file.getAbsolutePath());
        IOUtils.skipFully(in, skip);
        IOUtils.copyAndClose(in, out);
    }

}
