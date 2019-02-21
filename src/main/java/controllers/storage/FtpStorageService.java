package controllers.storage;

import org.apache.commons.io.IOUtils;
import org.apache.commons.vfs2.*;
import org.apache.commons.vfs2.provider.sftp.IdentityInfo;
import org.apache.commons.vfs2.provider.sftp.SftpFileSystemConfigBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Primary
public class FtpStorageService implements StorageService {

    @Autowired
    private Environment env;

    private FileSystemManager fsManager = null;
    private FileSystemOptions opts = null;

    private String host;
    private String user;
    private String password ;
    private String rootLocation;
    private String keyPath = null;
    private String passPhrase = null;

    @Autowired
    public FtpStorageService(StorageProperties properties) {
    }

    @Override
    public void store(MultipartFile file, String location) {
        String startPath;
        if (keyPath != null) {
            startPath = "sftp://" + user + "@" + host + rootLocation;
        } else {
            startPath = "sftp://" + user + ":" + password + "@" + host + rootLocation;
        }

        try {
            if (file.isEmpty()) {
                throw new StorageException("Failed to store empty file " + file.getOriginalFilename());
            }

            FileSystemManager fsManager2 = VFS.getManager();
            FileObject localFile = fsManager2.resolveFile("ram://tmp/" + file.getOriginalFilename());
            localFile.createFile();

            // more on https://thinkinginsoftware.blogspot.com/2012/01/commons-vfs-sftp-from-java-simple-way.html
            OutputStream localOutputStream = localFile.getContent().getOutputStream();
            try {
                IOUtils.copy(file.getInputStream(), localOutputStream);
                localOutputStream.flush();
            } catch (IOException e) {
                throw new StorageException("Failed to copy file to ram", e);
            }

            FileObject destination = fsManager.resolveFile(startPath + location + "/" + localFile.getName().getBaseName(), opts);
            if (!destination.getParent().exists()) {
                destination.getParent().createFolder();
            }

            destination.copyFrom(localFile, Selectors.SELECT_SELF);
        } catch (FileSystemException e) {
            throw new StorageException("Failed to store file", e);
        }
    }

    @Override
    public void move(String filename, String location) {
        String startPath;
        if (keyPath != null) {
            startPath = "sftp://" + user + "@" + host + rootLocation;
        } else {
            startPath = "sftp://" + user + ":" + password + "@" + host + rootLocation;
        }

        try {
            FileObject localFile = fsManager.resolveFile(startPath + filename, opts);

            FileObject destination = fsManager.resolveFile(startPath + location + "/" + localFile.getName().getBaseName(), opts);
            if (!destination.getParent().exists()) {
                destination.getParent().createFolder();
            }

            localFile.moveTo(destination);
        } catch (FileSystemException e) {
            throw new StorageException("Failed to move file", e);
        }
    }

    @Override
    public List<Map<String, String>> loadAll(String location) {
        List<Map<String, String>> list  = new ArrayList<>();

        String startPath;
        if (keyPath != null) {
            startPath = "sftp://" + user + "@" + host + rootLocation + location;
        } else {
            startPath = "sftp://" + user + ":" + password + "@" + host + rootLocation + location;
        }

        FileObject sftpFile;
        try {
            sftpFile = fsManager.resolveFile(startPath, opts);
        } catch (FileSystemException e) {
            throw new StorageException("SFTP error parsing path " + startPath, e);
        }

        try {
            FileObject[] children = sftpFile.getChildren();
            for (FileObject f : children) {
                if (f.getType() == FileType.FILE) {
                    Map<String, String> file = new HashMap<>();
                    file.put("filename", f.getName().getBaseName());
                    file.put("firstDate", "N/A"); // @deprecated
                    file.put("lastDate", "N/A"); // @deprecated
                    file.put("uploadedDate", new SimpleDateFormat("yyyy-MM-dd").format(f.getContent().getLastModifiedTime()));
                    list.add(file);
                }
            }
        } catch (FileSystemException e) {
            throw new StorageException("Failed to read stored files " + startPath, e);
        }
        return list;

    }

    @Override
    public void init() {
        this.host = env.getProperty("sftp.host");
        this.user = env.getProperty("sftp.username");
        this.password = env.getProperty("sftp.password");
        this.rootLocation = env.getProperty("sftp.dir");
        if (!env.getProperty("sftp.key").isEmpty()) {
            this.keyPath = env.getProperty("sftp.key");
        }
        if (!env.getProperty("sftp.passphrase").isEmpty()) {
            this.passPhrase = env.getProperty("sftp.passphrase");
        }

        try {
            fsManager = VFS.getManager();
        } catch (FileSystemException e) {
            throw new StorageException("failed to get fsManager from VFS", e);
        }

        opts = new FileSystemOptions();
        try {
            SftpFileSystemConfigBuilder.getInstance().setStrictHostKeyChecking(opts, "no");
            SftpFileSystemConfigBuilder.getInstance().setUserDirIsRoot(opts, false);
        } catch (FileSystemException e) {
            throw new StorageException("setUserAuthenticator failed", e);
        }

        IdentityInfo identityInfo;
        if (passPhrase != null) {
            identityInfo = new IdentityInfo(new File(keyPath), passPhrase.getBytes());
        } else {
            identityInfo =  new IdentityInfo(new File(keyPath));
        }
        try {
            SftpFileSystemConfigBuilder.getInstance().setIdentityInfo(opts, identityInfo);
        } catch (FileSystemException e) {
            throw new StorageException("setIdentityInfo failed", e);
        }
    }
}
