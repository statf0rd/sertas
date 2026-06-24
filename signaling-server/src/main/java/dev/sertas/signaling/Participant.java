package dev.sertas.signaling;

/** Участник в комнате (состояние сервера). */
public record Participant(String id, String name, String room) {}
