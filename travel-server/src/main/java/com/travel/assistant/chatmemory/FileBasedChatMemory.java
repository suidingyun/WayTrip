package com.travel.assistant.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Kryo 文件持久化的对话记忆（多轮会话按 chatId 分文件）。
 */
public class FileBasedChatMemory implements ChatMemory {

    private final String baseDir;
    private final int defaultLastN;
    private static final Kryo kryo = new Kryo();

    static {
        kryo.setRegistrationRequired(false);
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        kryo.register(ArrayList.class);
        kryo.register(UserMessage.class);
        kryo.register(AssistantMessage.class);
        kryo.register(SystemMessage.class);
    }

    public FileBasedChatMemory(String dir, int defaultLastN) {
        this.baseDir = dir;
        this.defaultLastN = defaultLastN;
        File d = new File(dir);
        if (!d.exists()) {
            d.mkdirs();
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> existing = getOrCreateConversation(conversationId);
        existing.addAll(messages);
        saveConversation(conversationId, existing);
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> all = getOrCreateConversation(conversationId);
        int n = defaultLastN;
        if (n <= 0 || n >= all.size()) {
            return List.copyOf(all);
        }
        return all.stream()
                .skip(Math.max(0, all.size() - n))
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        File file = getConversationFile(conversationId);
        if (file.exists()) {
            file.delete();
        }
    }

    private List<Message> getOrCreateConversation(String conversationId) {
        File file = getConversationFile(conversationId);
        List<Message> messages = new ArrayList<>();
        if (file.exists()) {
            try (Input input = new Input(new FileInputStream(file))) {
                ArrayList<?> raw = kryo.readObject(input, ArrayList.class);
                if (raw != null) {
                    for (Object o : raw) {
                        if (o instanceof Message m) {
                            messages.add(m);
                        }
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取会话记忆失败: " + conversationId, e);
            }
        }
        return messages;
    }

    private void saveConversation(String conversationId, List<Message> messages) {
        File file = getConversationFile(conversationId);
        try (Output output = new Output(new FileOutputStream(file))) {
            kryo.writeObject(output, new ArrayList<>(messages));
        } catch (IOException e) {
            throw new IllegalStateException("写入会话记忆失败: " + conversationId, e);
        }
    }

    private File getConversationFile(String conversationId) {
        String safeId = conversationId.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        return new File(baseDir, safeId + ".kryo");
    }
}
