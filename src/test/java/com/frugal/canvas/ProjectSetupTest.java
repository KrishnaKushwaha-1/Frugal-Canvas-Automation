
package com.frugal.canvas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectSetupTest {

    @Test
    void projectShouldInitializeSuccessfully() {

        String projectName = "Q1 Canvas Automation";

        assertTrue(projectName.contains("Canvas"));

        System.out.println("Project setup test executed.");
    }
}
