package io.joynr.exceptions;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2016 BMW Car IT GmbH
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

public class JoynrHttpException extends JoynrCommunicationException {

    private static final long serialVersionUID = -855603266419595137L;
    public final int statusCode;

    /**
     * Constructor for deserializer
     */
    protected JoynrHttpException() {
        super();
        statusCode = 0;
    }

    public JoynrHttpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    @Override
    public String toString() {
        return super.toString() + ": statusCode: " + statusCode;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + statusCode;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!super.equals(obj)) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        JoynrHttpException other = (JoynrHttpException) obj;
        if (statusCode != other.statusCode) {
            return false;
        }
        return true;
    }

}
