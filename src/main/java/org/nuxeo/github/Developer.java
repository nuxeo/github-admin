/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Julien Carsique
 *
 */

package org.nuxeo.github;

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.SystemUtils;
import org.apache.commons.lang.builder.CompareToBuilder;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.StandardToStringStyle;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.eclipse.egit.github.core.Contributor;
import org.eclipse.egit.github.core.User;

/**
 *
 */
public class Developer implements Comparable<Developer> {

    private String login;

    private Set<String> emails = new HashSet<>();

    private String url;

    private String company;

    private String name;

    private boolean anonymous;

    private Set<User> users = new HashSet<>();

    private static final StandardToStringStyle style;

    static {
        style = new StandardToStringStyle();
        style.setUseClassName(false);
        style.setUseIdentityHashCode(false);
        style.setUseFieldNames(true);
        style.setContentStart("[");
        style.setFieldSeparator(SystemUtils.LINE_SEPARATOR + "  ");
        style.setFieldSeparatorAtStart(true);
        style.setContentEnd(SystemUtils.LINE_SEPARATOR + "]");
        style.setNullText(null);
    }

    /**
     * Anonymous user (unknown login)
     *
     */
    public Developer(String name) {
        this.setAnonymous(true);
        this.name = name;
    }

    /**
     * GitHub user
     *
     */
    public Developer(String login, String url) {
        this.setAnonymous(false);
        this.login = login;
        this.url = url;
    }

    /**
     * @param contributor
     */
    public Developer(Contributor contributor) {
        if (contributor.getLogin() == null) {
            this.setAnonymous(true);
            this.name = contributor.getName();
        } else {
            this.setAnonymous(false);
            this.login = contributor.getLogin();
            this.url = contributor.getUrl();
        }
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public Set<String> getEmails() {
        return emails;
    }

    public void addEmail(String email) {
        emails.add(email);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isAnonymous() {
        return anonymous;
    }

    public void setAnonymous(boolean anonymous) {
        this.anonymous = anonymous;
    }

    /**
     * Associate with a GitHub user and copy available fields
     *
     * @param user Ignored if null
     */
    public void set(User user) {
        if (user == null) {
            return;
        }
        users.add(user);
        if (StringUtils.isBlank(name)) {
            name = user.getName();
        }
        if (StringUtils.isNotBlank(user.getEmail())
                && !emails.contains(user.getEmail())) {
            addEmail(user.getEmail());
        }
        if (StringUtils.isBlank(company)) {
            company = user.getCompany();
        }
        if (StringUtils.isBlank(url)) {
            url = user.getUrl();
        }
    }

    /**
     * Copy available fields
     *
     * @param remove
     */
    public void updateWith(Developer dev) {
        if (dev == null) {
            return;
        }
        if (StringUtils.isBlank(name)) {
            name = dev.getName();
        }
        emails.addAll(dev.getEmails());
        if (StringUtils.isBlank(company)) {
            company = dev.getCompany();
        }
        if (StringUtils.isBlank(url)) {
            url = dev.getUrl();
        }
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this, style) //
        .append("login", login) //
        .append("name", name) //
        .append("emails", emails) //
        .append("company", company) //
        .append("url", url) //
        .toString();
    }

    /**
     * @return true if all required fields have been filled
     */
    public boolean isComplete() {
        return (login != null && name != null && !emails.isEmpty());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Developer)) {
            return false;
        }
        Developer d = (Developer) o;
        return new EqualsBuilder() //
        .appendSuper(super.equals(d)) //
        .append(login, d.login) //
        .append(this.name, d.name) //
        .append(this.emails, d.emails) //
        .append(this.url, d.url) //
        .append(this.company, d.company) //
        .isEquals();
    }

    @Override
    public int compareTo(Developer o) {
        return new CompareToBuilder() //
        .append(this.login, o.login) //
        .append(this.name, o.name) //
        .append(this.emails, o.emails) //
        .append(this.url, o.url) //
        .append(this.company, o.company) //
        .toComparison();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder() //
        .append(login) //
        .append(name) //
        .append(emails) //
        .append(url) //
        .append(company) //
        .toHashCode();
    }
}
