/*
 * Copyright 2013-2017 EMC Corporation. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0.txt
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.emc.ecs.sync.config.filter;

import com.emc.ecs.sync.config.AbstractConfig;
import com.emc.ecs.sync.config.annotation.Documentation;
import com.emc.ecs.sync.config.annotation.FilterConfig;
import com.emc.ecs.sync.config.annotation.Label;
import com.emc.ecs.sync.config.annotation.Option;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
@FilterConfig(cliName = "restore-file-attributes")
@Label("Restore File Attributes")
@Documentation("This plugin will restore POSIX file attributes that were previously preserved in metadata on the object")
public class RestoreFileAttributesConfig extends AbstractConfig {
    private boolean failOnParseError = true;

    @Option(orderIndex = 10, advanced = true, cliInverted = true, description = "by default, if an error occurs parsing the attribute metadata, this will fail the object. disable this to only show a warning in that case")
    public boolean isFailOnParseError() {
        return failOnParseError;
    }

    public void setFailOnParseError(boolean failOnParseError) {
        this.failOnParseError = failOnParseError;
    }
}
