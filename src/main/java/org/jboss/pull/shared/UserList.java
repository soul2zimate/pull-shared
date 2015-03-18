/**
 * Internal Use Only
 *
 * Copyright 2011 Red Hat, Inc. All rights reserved.
 */
package org.jboss.pull.shared;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;

/**
 * @author Jason T. Greene
 */
public class UserList {

    private final HashSet<String> list;

    private UserList(HashSet<String> list) {
        this.list = list;
    }

    public static UserList loadUserList(String fileName) {
        HashSet<String> list = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty())
                    list.add(line.trim());
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Can not find user list file: " + fileName, e);
        } catch (IOException e) {
            throw new RuntimeException("Can not load user list file: " + fileName, e);
        }
        return new UserList(list);
    }

    public boolean has(String name) {
        return list.contains(name);
    }
}
