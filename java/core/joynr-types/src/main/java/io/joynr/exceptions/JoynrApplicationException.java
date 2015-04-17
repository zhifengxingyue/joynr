package io.joynr.exceptions;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2015 BMW Car IT GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

public class JoynrApplicationException extends Exception implements JoynrException {

    private static final long serialVersionUID = 6620625652713563976L;

    Enum error;

    public JoynrApplicationException(Enum error) {
        this.error = error;
    }

    public Enum getError() {
        return this.error;
    }
}
