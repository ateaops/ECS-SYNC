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
package sync.ui

import grails.validation.Validateable
import groovy.util.logging.Slf4j
import sync.ui.config.ConfigService

@Slf4j
class ScheduleEntry implements Validateable {
    static String prefix = "schedule/"

    static List<ScheduleEntry> list(ConfigService configService) {
        configService.listConfigObjects(prefix).collectMany {
            try {
                [new ScheduleEntry([configService: configService, xmlKey: it])]
            } catch (e) {
                log.warn "could not load schedule entry: ${it}", e
                [] // if we can't read it, skip it
            }
        }.sort { a, b -> a.name <=> b.name } // alphabetical order
    }

    ConfigService configService

    String name
    @Lazy
    volatile String xmlKey = "${prefix}${name}.xml"
    @Lazy
    volatile allKeys = [xmlKey]
    @Lazy(soft = true)
    volatile ScheduledSync scheduledSync = exists() ? configService.readConfigObject(xmlKey, ScheduledSync.class) : new ScheduledSync()

    boolean exists() {
        return (name && configService && configService.configObjectExists(xmlKey))
    }

    def write() {
        configService.writeConfigObject(xmlKey, scheduledSync, 'application/xml')
    }

    def setXmlKey(String key) {
        this.name = key.split('/').last().replaceFirst(/[.]xml$/, '')
    }

    static constraints = {
        configService nullable: true
        name blank: false
        // TODO: use @Option annotations to dynamically validate what we can before running the sync
        //scheduledSync validator: { it.validate() ?: 'invalid' }
    }
}
