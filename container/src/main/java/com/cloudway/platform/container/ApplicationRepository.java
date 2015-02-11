/**
 * Cloudway Platform
 * Copyright (c) 2012-2013 Cloudway Technology, Inc.
 * All rights reserved.
 */

package com.cloudway.platform.container;

import java.io.IOException;
import java.nio.file.Path;
import com.cloudway.platform.common.fp.io.IOFunction;

/**
 * This interface represents an application's source code repository.
 */
public interface ApplicationRepository
{
    static void setFactory(IOFunction<ApplicationContainer, ApplicationRepository> factory) {
        Helper.setApplicationRepositoryFactory(factory);
    }

    /**
     * Create a new instance of the application repository.
     */
    static ApplicationRepository of(ApplicationContainer container) throws IOException {
        return Helper.getApplicationRepository(container);
    }

    /**
     * Determines whether the repository exists.
     */
    boolean exists();

    /**
     * Populate the repository uses the provided directory to install
     * a template application.
     */
    void populateFromTemplate(Path basedir)
        throws IOException;

    /**
     * Populate the repository uses the provided URL to install
     * a template application.
     */
    void populateFromURL(String url)
        throws IOException;

    /**
     * Initialize an empty repository.
     */
    void populateEmpty()
        throws IOException;

    /**
     * Checkout repository into target.
     */
    void checkout(Path target)
        throws IOException;

    /**
     * Cleans up the repository.
     */
    void tidy() throws IOException;

    /**
     * Destroys the repository.
     */
    void destroy() throws IOException;
}
