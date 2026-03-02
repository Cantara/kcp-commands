package com.cantara.kcp.commands.model;

public record ParsedCommand(
        String key,         // manifest lookup key: "ls", "git-log", "docker-ps"
        String cmd,         // base command: "ls", "git", "docker"
        String subcommand   // nullable: "log", "ps"
) {}
