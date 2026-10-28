package com.flicknames.service.service;

import com.flicknames.service.entity.NameFamilyMember.Role;

import java.util.*;

/**
 * Builds name families from accepted links, without following links transitively (which would merge
 * Christopher with Katherine through Kit, and Andrea with Andrew through Andy):
 * <ol>
 *   <li>A short form is a name with an accepted DIMINUTIVE_OF link that is a nickname in use (not an
 *       independent name such as Liam); every spelling that folds like it is one too.</li>
 *   <li>The other names are full forms. Full forms joined by SPELLING_VARIANT links, or folding alike
 *       (José, Jose), form one group; a group larger than {@link #MAX_SPELLINGS} keeps only the folded ones.</li>
 *   <li>A family is a group with its short forms, and their short forms one step further (Nicky for Nick
 *       for Nicholas). It is labelled by its most common spelling. Groups with one spelling and no short
 *       forms are not families.</li>
 *   <li>A short form in several families is shared: listed in each.</li>
 * </ol>
 */
public final class NameFamilyRules {

    static final int MAX_SPELLINGS = 15;

    public record Name(long id, String spelling, String folded, long count) {}

    public record Link(long from, long to) {}

    public record Member(long nameId, Role role, boolean shared) {}

    public record Family(long headId, String label, List<Member> members) {}

    private NameFamilyRules() {
    }

    /**
     * @param diminutives accepted DIMINUTIVE_OF links used as nicknames (from = short form)
     * @param spellingVariants accepted SPELLING_VARIANT links
     */
    public static List<Family> build(Collection<Name> names, List<Link> diminutives, List<Link> spellingVariants) {
        Map<Long, Name> byId = new HashMap<>();
        Map<String, List<Long>> byFolded = new HashMap<>();
        for (Name n : names) {
            byId.put(n.id(), n);
            byFolded.computeIfAbsent(n.folded(), k -> new ArrayList<>()).add(n.id());
        }
        Set<String> shortFolded = new HashSet<>();
        for (Link d : diminutives) {
            Name s = byId.get(d.from());
            if (s != null) {
                shortFolded.add(s.folded());
            }
        }
        Map<Long, Set<Long>> shortFormsOf = new HashMap<>();
        for (Link d : diminutives) {
            if (byId.containsKey(d.from()) && byId.containsKey(d.to())) {
                shortFormsOf.computeIfAbsent(d.to(), k -> new HashSet<>()).add(d.from());
            }
        }

        // Groups of full forms
        UnionFind groups = new UnionFind();
        for (List<Long> same : byFolded.values()) {
            for (Long id : same) {
                if (!shortFolded.contains(byId.get(id).folded())) {
                    groups.union(same.get(0), id);
                }
            }
        }
        List<Link> fullVariants = spellingVariants.stream().filter(v -> isFull(byId, shortFolded, v.from())
            && isFull(byId, shortFolded, v.to())).toList();
        UnionFind withVariants = groups.copy();
        fullVariants.forEach(v -> withVariants.union(v.from(), v.to()));
        Map<Long, List<Long>> clusters = new HashMap<>();
        for (Name n : names) {
            if (isFull(byId, shortFolded, n.id())) {
                clusters.computeIfAbsent(withVariants.find(n.id()), k -> new ArrayList<>()).add(n.id());
            }
        }
        List<List<Long>> fullGroups = new ArrayList<>();
        for (List<Long> cluster : clusters.values()) {
            if (cluster.size() <= MAX_SPELLINGS) {
                fullGroups.add(cluster);
            } else {
                Map<Long, List<Long>> folded = new HashMap<>();
                cluster.forEach(id -> folded.computeIfAbsent(groups.find(id), k -> new ArrayList<>()).add(id));
                fullGroups.addAll(folded.values());
            }
        }

        List<Family> families = new ArrayList<>();
        Map<Long, Integer> familiesPerShortForm = new HashMap<>();
        List<Map.Entry<List<Long>, Set<Long>>> drafts = new ArrayList<>();
        for (List<Long> group : fullGroups) {
            Set<Long> shorts = new LinkedHashSet<>();
            for (Long full : group) {
                for (Long s : shortFormsOf.getOrDefault(full, Set.of())) {
                    shorts.addAll(sameFolded(byId, byFolded, s));
                }
            }
            Set<Long> secondStep = new LinkedHashSet<>();
            for (Long s : shorts) {
                for (Long t : shortFormsOf.getOrDefault(s, Set.of())) {
                    secondStep.addAll(sameFolded(byId, byFolded, t));
                }
            }
            shorts.addAll(secondStep);
            group.forEach(shorts::remove);
            if (group.size() < 2 && shorts.isEmpty()) {
                continue;
            }
            shorts.forEach(s -> familiesPerShortForm.merge(s, 1, Integer::sum));
            drafts.add(Map.entry(group, shorts));
        }
        for (Map.Entry<List<Long>, Set<Long>> draft : drafts) {
            Name head = draft.getKey().stream().map(byId::get)
                .max(Comparator.comparingLong(Name::count).thenComparing(Name::spelling, Comparator.reverseOrder()))
                .orElseThrow();
            List<Member> members = new ArrayList<>();
            members.add(new Member(head.id(), Role.HEAD, false));
            draft.getKey().stream().filter(id -> id != head.id()).sorted()
                .forEach(id -> members.add(new Member(id, Role.SPELLING, false)));
            draft.getValue().stream().sorted()
                .forEach(id -> members.add(new Member(id, Role.SHORT_FORM, familiesPerShortForm.get(id) > 1)));
            families.add(new Family(head.id(), head.spelling(), members));
        }
        families.sort(Comparator.comparing(Family::label));
        return families;
    }

    private static boolean isFull(Map<Long, Name> byId, Set<String> shortFolded, long id) {
        Name n = byId.get(id);
        return n != null && !shortFolded.contains(n.folded());
    }

    private static List<Long> sameFolded(Map<Long, Name> byId, Map<String, List<Long>> byFolded, long id) {
        return byFolded.getOrDefault(byId.get(id).folded(), List.of(id));
    }

    private static final class UnionFind {
        private final Map<Long, Long> parent = new HashMap<>();

        long find(long x) {
            Long p = parent.get(x);
            if (p == null) {
                return x;
            }
            long root = find(p);
            parent.put(x, root);
            return root;
        }

        void union(long a, long b) {
            long ra = find(a);
            long rb = find(b);
            if (ra != rb) {
                parent.put(Math.max(ra, rb), Math.min(ra, rb));
            }
        }

        UnionFind copy() {
            UnionFind c = new UnionFind();
            c.parent.putAll(parent);
            return c;
        }
    }
}
