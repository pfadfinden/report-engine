package de.pfadfinden.reports_engine.preprocessor.Util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public class FindMatchingFilesFileVisitor extends SimpleFileVisitor<Path> {
    public List<Path> matchesList = new ArrayList<Path>();
    private PathMatcher matcher;

    public FindMatchingFilesFileVisitor(String globOrRegex) {
        this.matcher = FileSystems.getDefault().getPathMatcher(globOrRegex);
    };

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attribs) throws IOException {
        Path name = file.getFileName();
        if (matcher.matches(name)) {
            matchesList.add(file);
        }
        return FileVisitResult.CONTINUE;
    }
};