package com.flicknames.service.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A spelling in a name family. A short form in more than one family (Chris: Christopher, Christina) is
 * {@code shared}: listed in each, counted in none.
 */
@Entity
@Table(name = "name_family_members",
    uniqueConstraints = @UniqueConstraint(name = "uk_name_family_member", columnNames = {"family_id", "given_name_id"}),
    indexes = @Index(name = "idx_name_family_member_name", columnList = "given_name_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NameFamilyMember {

    public enum Role { HEAD, SPELLING, SHORT_FORM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false)
    private Long familyId;

    @Column(name = "given_name_id", nullable = false)
    private Long givenNameId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Role role;

    @Column(nullable = false)
    private boolean shared;
}
