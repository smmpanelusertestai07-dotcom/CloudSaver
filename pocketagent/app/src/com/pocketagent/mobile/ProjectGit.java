package com.pocketagent.mobile;

import java.io.IOException;
import java.net.URI;

/** Fixed, quoted Git operations; repository URLs never become shell syntax or transport helpers. */
final class ProjectGit {
    static final String COMMAND = "git -c core.hooksPath=/dev/null -c core.fsmonitor=false -c core.quotePath=false"
            + " -c protocol.allow=never -c protocol.https.allow=always -c http.sslVerify=true";
    private ProjectGit() {}

    static String remote(String value) throws IOException {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getPath() == null
                    || uri.getPath().isEmpty() || uri.getPath().equals("/"))
                throw new IOException("Use a plain HTTPS repository URL without passwords or tokens.");
            return uri.toASCIIString();
        } catch (java.net.URISyntaxException error) { throw new IOException("This repository URL is not valid.", error); }
    }
    static void branch(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}") || value.contains("..")
                || value.contains("//") || value.endsWith("/") || value.endsWith(".") || value.endsWith(".lock"))
            throw new IOException("Use a normal named branch before syncing; detached HEAD is not supported here.");
    }
    static String syncCommand(String url, String name, boolean push) throws IOException {
        return syncCommand(url, name, push, false);
    }
    static String syncCommand(String url, String name, boolean push, boolean verifiedGitHub) throws IOException {
        url = remote(url); branch(name);
        String operation = push ? " push --porcelain -- " + quote(url) + " " + quote("HEAD:refs/heads/" + name)
                : " pull --ff-only --no-rebase --no-autostash --no-edit -- " + quote(url) + " " + quote(name);
        return "GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/bin/false " + commandForRemote(url, verifiedGitHub) + operation;
    }
    static String reviewedSyncCommand(String reviewedUrl, String reviewedBranch, String actualUrl, String actualBranch,
                                       boolean push) throws IOException {
        return reviewedSyncCommand(reviewedUrl, reviewedBranch, actualUrl, actualBranch, push, false);
    }
    static String reviewedSyncCommand(String reviewedUrl, String reviewedBranch, String actualUrl, String actualBranch,
                                       boolean push, boolean verifiedGitHub) throws IOException {
        String expected = remote(reviewedUrl), actual = remote(actualUrl);
        branch(reviewedBranch); branch(actualBranch);
        if (!expected.equals(actual) || !reviewedBranch.equals(actualBranch))
            throw new IOException("The repository destination or branch changed after you reviewed it. Refresh Git status and confirm the new destination before syncing.");
        return syncCommand(expected, reviewedBranch, push, verifiedGitHub);
    }
    static String commandForRemote(String url, boolean verifiedGitHub) throws IOException {
        String checked = remote(url);
        if (verifiedGitHub && "github.com".equalsIgnoreCase(URI.create(checked).getHost()))
            return COMMAND + " -c credential.https://github.com.helper= -c "
                    + quote("credential.https://github.com.helper=!env -u GH_TOKEN -u GITHUB_TOKEN -u GH_ENTERPRISE_TOKEN"
                    + " -u GITHUB_ENTERPRISE_TOKEN -u GH_DEBUG -u DEBUG -u XDG_CONFIG_HOME"
                    + " HOME=/root GH_HOST=github.com GH_CONFIG_DIR=/root/.config/gh /usr/bin/gh auth git-credential");
        return COMMAND;
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
